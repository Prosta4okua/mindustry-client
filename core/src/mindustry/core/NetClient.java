package mindustry.core;

import arc.*;
import arc.audio.*;
import arc.func.*;
import arc.graphics.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.CommandHandler.*;
import arc.util.*;
import arc.util.io.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.annotations.Annotations.*;
import mindustry.client.*;
import mindustry.client.communication.*;
import mindustry.client.utils.*;
import mindustry.core.GameState.*;
import mindustry.entities.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.io.*;
import mindustry.logic.*;
import mindustry.net.Administration.*;
import mindustry.net.*;
import mindustry.net.Packets.*;
import mindustry.ui.*;
import mindustry.ui.fragments.*;
import mindustry.world.*;
import mindustry.world.modules.*;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

import static mindustry.Vars.*;

public class NetClient implements ApplicationListener{
    private static final float dataTimeout = 60 * 30; // Give up after 30s (vanilla is 20s)
    /** ticks between syncs, e.g. 5 means 60/5 = 12 syncs/sec*/
    private static final float playerSyncTime = 4;
    private static final Reads dataReads = new Reads(null);
    private static final Pattern wholeCoordPattern = Pattern.compile("\\S*?(\\d+)(?:\\[[^]]*])*(?:\\s|,)+(?:\\[[^]]*])*(\\d+)\\S*"); // This regex is a mess, it captures the coords into $1 and $2 while $0 contains all surrounding text as well. https://regex101.com is the superior regex tester
    private static final Pattern coordPattern = Pattern.compile("(\\d+)(?:\\[[^]]*])*(?:\\s|,)+(?:\\[[^]]*])*(\\d+)"); // Same as above, but without the surrounding text and https://regexr.com
    private static final Pattern linkPattern = Pattern.compile("(https?://)?[-a-zA-Z0-9@:%._\\\\+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b[-a-zA-Z0-9()@:%_\\\\+.~#?&/=]*");

    private long ping;
    private Interval timer = new Interval(5);
    /** Whether the client is currently connecting. */
    private boolean connecting = false;
    /** If true, no message will be shown on disconnect. */
    private boolean quiet = false;
    /** Whether to suppress disconnect events completely.*/
    private boolean quietReset = false;
    /** Counter for data timeout. */
    private float timeoutTime = 0f;
    /** Last sent client snapshot ID. */
    private int lastSent;

    /** List of entities that were removed, and need not be added while syncing. */
    private IntSet removed = new IntSet();
    /** Byte stream for reading in snapshots. */
    private ReusableByteInStream byteStream = new ReusableByteInStream();
    private DataInputStream dataStream = new DataInputStream(byteStream);
    /** Packet handlers for custom types of messages. */
    private ObjectMap<String, Seq<Cons<String>>> customPacketHandlers = new ObjectMap<>();
    /** Foo's thing to make ServerJoinEvent work good */
    public static boolean firstLoad = true;

    public NetClient(){

        net.handleClient(Connect.class, packet -> {
            Log.info("Connecting to server: @", packet.addressTCP);

            player.admin = false;

            reset();

            //connection after reset
            if(!net.client()){
                Log.info("Connection canceled.");
                disconnectQuietly();
                return;
            }

            ui.loadfrag.hide();
            ui.loadfrag.show("@connecting.data");

            ui.loadfrag.setButton(() -> {
                ui.loadfrag.hide();
                disconnectQuietly();
            });

            String locale = Core.settings.getString("locale");
            if(locale.equals("default")){
                locale = Locale.getDefault().toString();
            }

            var c = new ConnectPacket();
            c.name = player.name;
            c.locale = locale;
            c.mods = mods.getModStrings();
            c.mobile = mobile;
            c.versionType = Version.type;
            c.color = player.color.rgba();
            c.usid = getUsid(packet.addressTCP);
            c.uuid = platform.getUUID();

            var address = packet.addressTCP.split("[:/]")[1]; // Remove leading slash (and domain) and trailing port
            if (ui.join.communityHosts.contains(h -> "Korea".equals(h.group) && h.address.equals(address))) { // Korea is cursed
                var matcher = Pattern.compile("^\\[(.*)]").matcher(player.name);
                if (matcher.find()) {
                    var col = matcher.toMatchResult().group(1);
                    var get = Colors.get(col);
                    c.name = matcher.replaceAll("");
                    try {
                        c.color = get != null ? get.rgba() : Color.valueOf(col).rgba();
                    } catch (IndexOutOfBoundsException ignored) {}
                }
            } else if (ui.join.communityHosts.contains(h -> "Chaotic Neutral".equals(h.group) && h.address.equals(address))) {
                if (!Structs.contains(playerColors, col -> col.rgba() == c.color)) c.color = playerColors[0].rgba();
            }

            if(c.uuid == null){
                ui.showErrorMessage("@invalidid");
                ui.loadfrag.hide();
                disconnectQuietly();
                return;
            }

            net.send(c, true);
        });

        net.handleClient(Disconnect.class, packet -> {
            if(quietReset) return;

            connecting = false;
            logic.reset();
            platform.updateRPC();
            player.name = Core.settings.getString("name");
            player.color.set(Core.settings.getInt("color-0"));

            if(quiet) return;

            Time.runTask(3f, ui.loadfrag::hide);

            String title = 
                packet.reason == null ? "@disconnect" :
                packet.reason.equals("closed") ? "@disconnect.closed" :
                packet.reason.equals("timeout") ? "@disconnect.timeout" :
                "@disconnect.error";
            ui.showCustomConfirm(title, "@disconnect.closed", "@reconnect", "@ok", () -> ui.join.reconnect(), () -> {});
            //FINISHME: duped code, ctrl+f ui.showCustomConfirm
        });

        net.handleClient(WorldStream.class, data -> {
            Log.info("Received world data: @ bytes.", data.stream.available());
            NetworkIO.loadWorld(new InflaterInputStream(data.stream));

            finishConnecting();
        });
    }

    public void addPacketHandler(String type, Cons<String> handler){
        customPacketHandlers.get(type, Seq::new).add(handler);
    }

    public Seq<Cons<String>> getPacketHandlers(String type){
        return customPacketHandlers.get(type, Seq::new);
    }

    @Remote(targets = Loc.server, variants = Variant.both)
    public static void clientPacketReliable(String type, String contents){
        if(netClient.customPacketHandlers.containsKey(type)){
            for(Cons<String> c : netClient.customPacketHandlers.get(type)){
                c.get(contents);
            }
        }
    }

    @Remote(targets = Loc.server, variants = Variant.both, unreliable = true)
    public static void clientPacketUnreliable(String type, String contents){
        clientPacketReliable(type, contents);
    }

    @Remote(variants = Variant.both, unreliable = true, called = Loc.server)
    public static void sound(Sound sound, float volume, float pitch, float pan){
        if(sound == null || headless) return;

        sound.play(Mathf.clamp(volume, 0, 8f) * Core.settings.getInt("sfxvol") / 100f, Mathf.clamp(pitch, 0f, 20f), pan, false, false);
    }

    @Remote(variants = Variant.both, unreliable = true, called = Loc.server)
    public static void soundAt(Sound sound, float x, float y, float volume, float pitch){
        if(sound == null || headless) return;
        if(sound == Sounds.corexplode && Server.io.b()) return;

        sound.at(x, y, Mathf.clamp(pitch, 0f, 20f), Mathf.clamp(volume, 0, 4f));
    }

    @Remote(variants = Variant.both, unreliable = true)
    public static void effect(Effect effect, float x, float y, float rotation, Color color){
        if(effect == null) return;

        effect.at(x, y, rotation, color);
    }

    @Remote(variants = Variant.both, unreliable = true)
    public static void effect(Effect effect, float x, float y, float rotation, Color color, Object data){
        if(effect == null) return;

        effect.at(x, y, rotation, color, data);
    }

    @Remote(variants = Variant.both)
    public static void effectReliable(Effect effect, float x, float y, float rotation, Color color){
        effect(effect, x, y, rotation, color);
    }

    @Remote(targets = Loc.server, variants = Variant.both)
    public static void sendMessage(String message, @Nullable String unformatted, @Nullable Player playersender){
        // message is the full formatted message from the server, including the sender
        // unformatted is the message content itself, i.e. "gg", null for server messages
        // playersender is exactly what you think it is, null for server messages
        if(Server.current.handleMessage(message, unformatted, playersender)) return;

        Events.fire(new PlayerChatEvent(playersender, unformatted != null ? unformatted : message != null ? message : "")); // Foo addition, why is this not a vanilla thing?

        Color background = null;
        if(Vars.ui != null){
            var prefix = "";

            // Add wrench to client user messages, highlight if enabled
            if (playersender != null && playersender.fooUser && playersender != player) {
                prefix = Iconc.wrench + " ";
                if (Core.settings.getBool("highlightclientmsg")) background = ClientVars.user;
            }

            if (Core.settings.getBool("logmsgstoconsole") && net.client()) // Make sure we are a client, if we are the server it does this already
                Log.log(Log.LogLevel.info, "[Chat] &fi@: @",
                    "&lc" + (playersender == null ? "Server" : Strings.stripColors(playersender.name)),
                    "&lw" + Strings.stripColors(InvisibleCharCoder.INSTANCE.strip(unformatted != null ? unformatted : message))
                );
            
            // highlight coords and set as the last position
            unformatted = processCoords(unformatted, true);
            message = processCoords(message, unformatted != null);

            ChatFragment.ChatMessage output;

            if (playersender != null) {
                if (ClientVars.mutedPlayers.contains( p -> p.getSecond() == playersender.id || (p.getFirst() != null && playersender.name.equals(p.getFirst().name)))) {
                    return; // Just ignore them
                }
                // from a player
                if (message != null) { // The Korea server breaks the rules of this method and has a null message
                    // if it's an admin or team message, incorporate that into the prefix because the original formatting will be discarded
                    if (message.startsWith("[#" + playersender.team().color.toString() + "]<T>")) {
                        prefix += "[#" + playersender.team().color.toString() + "]<T> ";
                    } else if (message.startsWith("[#" + Pal.adminChat.toString() + "]<A>")) {
                        prefix += "[#" + Pal.adminChat.toString() + "]<A> ";
                    }
                }

                // I don't think this even works
//                var unformatted2 = unformatted == null ? StringsKt.removePrefix(message, "[" + playersender.coloredName() + "]: ") : unformatted;
                output = ui.chatfrag.addMessage(message, playersender.coloredName(), background, prefix, unformatted);
                output.addButton(output.formattedMessage.indexOf(playersender.coloredName()), playersender.coloredName().length() + 16 + output.prefix.length(), () -> Spectate.INSTANCE.spectate(playersender));
            } else {
                // server message, unformatted is ignored
                output = ui.chatfrag.addMessage(message, null, null, "", "");
            }

            findCoords(output);
            findLinks(output, playersender == null ? 0 : playersender.coloredName().length() + 16 + output.prefix.length());

            Sounds.chatMessage.play();
        }

        if(playersender != null && unformatted != null){
            //display raw unformatted text above player head
            playersender.lastText(unformatted);
            playersender.textFadeTime(1f);
        }

        Events.fire(new PlayerChatEventClient());
    }

    //equivalent to above method but there's no sender and no console log
    @Remote(called = Loc.server, targets = Loc.server)
    public static void sendMessage(String message){
        if(Server.current.handleMessage(message, message, null)) return;
        if(Vars.ui == null) return;

        if (Core.settings.getBool("logmsgstoconsole") && net.client()) Log.infoTag("Chat", Strings.stripColors(InvisibleCharCoder.INSTANCE.strip(message)));
        if (!message.contains("has connected") && !message.contains("has disconnected")) Log.debug("Tell the owner of this server to send messages properly");
        message = processCoords(message, true);
        var output = Vars.ui.chatfrag.addMessage(message, null, null, "", message);

        findCoords(output);
        findLinks(output, 0);

        if (Server.current.isVotekick(message)) { // Vote kick clickable buttons
            String yes = Core.bundle.get("client.voteyes"), no = Core.bundle.get("client.voteno");
            output.message = output.message + '\n' + yes + "  " + no;
            output.format();
            output.addButton(yes, () -> Call.sendChatMessage("/vote y"));
            output.addButton(no, () -> Call.sendChatMessage("/vote n"));
        }

        Server.current.handleVoteButtons(output);

        Sounds.chatMessage.play();
    }

    public static class FoundCoords {
        public Vec2 pos;
        public int start, end;
    }

    public static Seq<FoundCoords> findCoords(String message) {
        if (message == null) return new Seq<>();
        Matcher matcher = coordPattern.matcher(message);
        Seq<FoundCoords> out = new Seq<>();
        while (matcher.find()) {
            var result = matcher.toMatchResult();
            try {
                var pos = new Vec2(Float.parseFloat(result.group(1)) * tilesize, Float.parseFloat(result.group(2)) * tilesize);
                var coord = new FoundCoords();
                coord.pos = pos;
                coord.start = result.start();
                coord.end = result.end();
                out.add(coord);
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /** Finds coordinates in a message and makes them clickable */
    public static ChatFragment.ChatMessage findCoords(ChatFragment.ChatMessage msg) {
        findCoords(InvisibleCharCoder.INSTANCE.strip(msg.formattedMessage))
            .each(c -> msg.addButton(c.start, c.end, () -> Spectate.INSTANCE.spectate(c.pos)));
        return msg;
    }

    /** Finds links in a message and makes them clickable */
    public static ChatFragment.ChatMessage findLinks(ChatFragment.ChatMessage msg, int start) {
        Matcher matcher = linkPattern.matcher(InvisibleCharCoder.INSTANCE.strip(msg.formattedMessage));
        while (matcher.find()) {
            var res = matcher.toMatchResult();
            if(res.start() < start) continue; // .find(start) is cursed
            var url = res.group(1) == null ? "https://" + res.group() : res.group(); // Add https:// if missing protocol
            msg.addButton(res.start(), res.end(), () -> Menus.openURI(url));
        }
        return msg;
    }

    public static String processCoords(String message, boolean setLastPos){
        // FINISHME: use findCoords()
        if (message == null) return null;
        Matcher matcher = wholeCoordPattern.matcher(message);
        if (!matcher.find()) return message;
        if (setLastPos) try {
            ClientVars.lastSentPos.set(Float.parseFloat(matcher.group(1)), Float.parseFloat(matcher.group(2)));
        } catch (NumberFormatException ignored) {}
        return matcher.replaceFirst(Matcher.quoteReplacement("[scarlet]" + Strings.stripColors(matcher.group()) + "[]")); // replaceFirst [scarlet]$0[] fails if $0 begins with a color, stripColors($0) isn't something that works.
    }

    //called when a server receives a chat message from a player
    @Remote(called = Loc.server, targets = Loc.client)
    public static void sendChatMessage(Player player, String message){
        //do not receive chat messages from clients that are too young or not registered
        if(net.server() && player != null && player.con != null && (Time.timeSinceMillis(player.con.connectTime) < 500 || !player.con.hasConnected || !player.isAdded())) return;

        //detect and kick for foul play
        if(player != null && player.con != null && !player.con.chatRate.allow(2000, Config.chatSpamLimit.num())){
            player.con.kick(KickReason.kick);
            netServer.admins.blacklistDos(player.con.address);
            return;
        }

        if(message == null) return;

        if(message.length() > maxTextLength){
            throw new ValidateException(player, "Player has sent a message above the text limit.");
        }

        message = message.replace("\n", "");

        Events.fire(new PlayerChatEvent(player, message));

        //log commands before they are handled
        if(message.startsWith(netServer.clientCommands.getPrefix())){
            //log with brackets
            Log.info("<&fi@: @&fr>", "&lk" + player.plainName(), "&lw" + message);
        }

        //check if it's a command
        CommandResponse response = netServer.clientCommands.handleMessage(message, player);
        if(response.type == ResponseType.noCommand){ //no command to handle
            message = netServer.admins.filterMessage(player, message);
            //suppress chat message if it's filtered out
            if(message == null){
                return;
            }

            //special case; graphical server needs to see its message
            if(!headless){
                sendMessage(netServer.chatFormatter.format(player, message), message, player);
            }

            //server console logging
            Log.info("&fi@: @", "&lc" + player.plainName(), "&lw" + InvisibleCharCoder.INSTANCE.strip(message));

            //invoke event for all clients but also locally
            //this is required so other clients get the correct name even if they don't know who's sending it yet
            Call.sendMessage(netServer.chatFormatter.format(player, message), message, player);
        }else{

            //a command was sent, now get the output
            if(response.type != ResponseType.valid){
                String text = netServer.invalidHandler.handle(player, response);
                if(text != null){
                    player.sendMessage(text);
                }
            }
        }
    }

    @Remote(called = Loc.client, variants = Variant.one)
    public static void connect(String ip, int port){
        if(!steam && ip.startsWith("steam:")) return;
        Log.info("Server sending us to @:@", ip, port);
        netClient.disconnectQuietly();
        logic.reset();

        Server.destinationServer = ip + ":" + port;
        ui.join.connect(ip, port);
    }

    @Remote(targets = Loc.client)
    public static void ping(Player player, long time){
        Call.pingResponse(player.con, time);
    }

    @Remote(variants = Variant.one)
    public static void pingResponse(long time){
        netClient.ping = Time.timeSinceMillis(time);
    }

    @Remote(variants = Variant.one)
    public static void traceInfo(Player player, TraceInfo info){
        if(player != null){
            if (ClientVars.silentTrace == 0) ui.traces.show(player, info);
            else {
                if (Core.settings.getBool("modenabled")) Client.INSTANCE.getLeaves().addInfo(player, info);
                ClientVars.silentTrace--;
            }
        }
    }

    @Remote(variants = Variant.one, priority = PacketPriority.high)
    public static void kick(KickReason reason){
        netClient.disconnectQuietly();
        logic.reset();

        if(reason == KickReason.serverRestarting){
            ui.join.reconnect();
            return;
        }

        if(!reason.quiet){
            String title = reason.extraText() == null ? "@disconnect" : reason.toString();
            String text = reason.extraText() == null ? reason.toString() : reason.extraText();

            if(reason.rejoinable){
                ui.showCustomConfirm(title, text, "@reconnect", "@ok", () -> ui.join.reconnect(), () -> {});
            }else{
                ui.showText(reason.toString(), reason.extraText());
            }
        }
        ui.loadfrag.hide();
    }

    @Remote(variants = Variant.one, priority = PacketPriority.high)
    public static void kick(String reason){
        ServerUtils.handleKick(reason);
        netClient.disconnectQuietly();
        logic.reset();
        ui.showCustomConfirm("@disconnect", reason, "@reconnect", "@ok", () -> ui.join.reconnect(), () -> {});
        ui.loadfrag.hide();
    }

    @Remote(variants = Variant.both)
    public static void setRules(Rules rules){
        state.rules = rules;
    }

    @Remote(variants = Variant.both)
    public static void setObjectives(MapObjectives executor){
        //clear old markers
        for(var objective : state.rules.objectives){
            for(var marker : objective.markers){
                if(marker.wasAdded){
                    marker.removed();
                    marker.wasAdded = false;
                }
            }
        }

        state.rules.objectives = executor;
    }

    @Remote(called = Loc.server)
    public static void objectiveCompleted(String[] flagsRemoved, String[] flagsAdded){
        state.rules.objectiveFlags.removeAll(flagsRemoved);
        state.rules.objectiveFlags.addAll(flagsAdded);
    }

    @Remote(variants = Variant.both)
    public static void worldDataBegin(){
        Groups.clear();
        netClient.removed.clear();
        logic.reset();
        netClient.connecting = true;

        net.setClientLoaded(false);

        ui.loadfrag.show("@connecting.data");

        ui.loadfrag.setButton(() -> {
            ui.loadfrag.hide();

            netClient.disconnectQuietly();
        });
    }

    @Remote(variants = Variant.one)
    public static void setPosition(float x, float y){
        player.unit().set(x, y);
        player.set(x, y);
    }

    @Remote(variants = Variant.both, unreliable = true)
    public static void setCameraPosition(float x, float y){ // FINISHME: Add some sort of toggle
        if(Core.camera != null){
            Core.camera.position.set(x, y);
        }
    }

    @Remote
    public static void playerDisconnect(int playerid){
        Events.fire(new PlayerLeave(Groups.player.getByID(playerid)));
        if(netClient != null){
            netClient.addRemovedEntity(playerid);
        }
        Groups.player.removeByID(playerid);
    }

    public static void readSyncEntity(DataInputStream input, Reads read) throws IOException{
        int id = input.readInt();
        byte typeID = input.readByte();

        Syncc entity = Groups.sync.getByID(id);
        boolean add = false, created = false;

        if(entity == null && id == player.id()){
            entity = player;
            add = true;
        }

        //entity must not be added yet, so create it
        if(entity == null){
            entity = (Syncc)EntityMapping.map(typeID & 0xFF).get();
            entity.id(id);
            if(!netClient.isEntityUsed(entity.id())){
                add = true;
            }
            created = true;
        }

        //read the entity
        entity.readSync(read);

        if(created){
            //snap initial starting position
            entity.snapSync();
        }

        if(add){
            entity.add();
            netClient.addRemovedEntity(entity.id());
            if (entity instanceof Player p && !ClientVars.syncing) Events.fire(new PlayerJoin(p));
        }
    }

    @Remote(variants = Variant.one, priority = PacketPriority.low, unreliable = true)
    public static void entitySnapshot(short amount, byte[] data){
        try{
            netClient.byteStream.setBytes(data);
            DataInputStream input = netClient.dataStream;

            for(int j = 0; j < amount; j++){
                readSyncEntity(input, Reads.get(input));
            }
        }catch(Exception e){
            //don't disconnect, just log it
            Log.err("Error reading entity snapshot", e);
        }
    }

    @Remote(variants = Variant.one, priority = PacketPriority.low, unreliable = true)
    public static void hiddenSnapshot(IntSeq ids){
        for(int i = 0; i < ids.size; i++){
            int id = ids.items[i];
            var entity = Groups.sync.getByID(id);
            if(entity != null){
                entity.handleSyncHidden();
            }
        }
    }

    @Remote(variants = Variant.both, priority = PacketPriority.low, unreliable = true)
    public static void blockSnapshot(short amount, byte[] data){
        try{
            netClient.byteStream.setBytes(data);
            DataInputStream input = netClient.dataStream;

            for(int i = 0; i < amount; i++){
                int pos = input.readInt();
                short block = input.readShort();
                Tile tile = world.tile(pos);
                if(tile == null || tile.build == null){
                    Log.warn("Missing entity at @. Skipping block snapshot.", tile);
                    break;
                }
                if(tile.build.block.id != block){
                    Log.warn("Block ID mismatch at @: @ != @. Skipping block snapshot.", tile, tile.build.block.id, block);
                    break;
                }
                tile.build.readAll(Reads.get(input), tile.build.version());
            }
        }catch(Exception e){
            Log.err(e);
        }
    }

    @Remote(variants = Variant.one, priority = PacketPriority.low, unreliable = true)
    public static void stateSnapshot(float waveTime, int wave, int enemies, boolean paused, boolean gameOver, int timeData, byte tps, long rand0, long rand1, byte[] coreData){
        try{
            if(wave > state.wave){
                state.wave = wave;
                Events.fire(new WaveEvent());
            }

            state.gameOver = gameOver;
            state.wavetime = waveTime;
            state.wave = wave;
            state.enemies = enemies;
            if(!state.isMenu()){
                state.set(paused ? State.paused : State.playing);
            }
            state.serverTps = tps & 0xff;

            //note that this is far from a guarantee that random state is synced - tiny changes in delta and ping can throw everything off again.
            //syncing will only make much of a difference when rand() is called infrequently
            GlobalVars.rand.seed0 = rand0;
            GlobalVars.rand.seed1 = rand1;

            universe.updateNetSeconds(timeData);

            netClient.byteStream.setBytes(coreData);
            DataInputStream input = netClient.dataStream;
            dataReads.input = input;

            int teams = input.readUnsignedByte();
            for(int i = 0; i < teams; i++){
                int team = input.readUnsignedByte();
                TeamData data = Team.all[team].data();
                if(data.cores.any()){
                    data.cores.first().items.read(dataReads);
                }else{
                    new ItemModule().read(dataReads);
                }
            }

        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public void update(){
        if(!net.client()) return;

        if(state.isGame()){
            if(!connecting) sync();
        }else if(!connecting){
            net.disconnect();
        }else{ //...must be connecting
            timeoutTime += Time.delta;
            if(timeoutTime > dataTimeout){
                Log.err("Failed to load data!");
                ui.loadfrag.hide();
                quiet = true;
                ui.showErrorMessage("@disconnect.data");
                net.disconnect();
                timeoutTime = 0f;
            }
        }
    }

    /** Resets the world data timeout counter. */
    public void resetTimeout(){
        timeoutTime = 0f;
    }

    public boolean isConnecting(){
        return connecting;
    }

    public int getPing(){
        return (int)ping;
    }

    private void finishConnecting(){
        state.set(State.playing);
        connecting = false;
        ui.join.hide();
        net.setClientLoaded(true);
        Call.connectConfirm();
        Time.runTask(40f, platform::updateRPC);
        Core.app.post(ui.loadfrag::hide);
        if (NetClient.firstLoad) {
            Events.fire(new ServerJoinEvent());
            NetClient.firstLoad = false;
        }
    }

    private void reset(){
        firstLoad = true;
        net.setClientLoaded(false);
        removed.clear();
        timeoutTime = 0f;
        connecting = true;
        quietReset = false;
        quiet = false;
        lastSent = 0;

        Groups.clear();
        ui.chatfrag.clearMessages();
    }

    public void beginConnecting(){
        connecting = true;
    }

    /** Disconnects, resetting state to the menu. */
    public void disconnectQuietly(){
        quiet = true;
        connecting = false;
        net.disconnect();
    }

    /** Disconnects, causing no further changes or reset.*/
    public void disconnectNoReset(){
        quiet = quietReset = true;
        net.disconnect();
    }

    /** When set, any disconnects will be ignored and no dialogs will be shown. */
    public void setQuiet(){
        quiet = true;
    }

    public void clearRemovedEntity(int id){
        removed.remove(id);
    }

    public void addRemovedEntity(int id){
        removed.add(id);
    }

    public boolean isEntityUsed(int id){
        return removed.contains(id);
    }

    void sync(){
        if(timer.get(0, playerSyncTime)){
            Unit unit = player.dead() ? Nulls.unit : player.unit();
            int uid = player.dead() ? -1 : unit.id;
            Vec2 aimPos = Main.INSTANCE.floatEmbed();

            TypeIO.useConfigLocal = true; // Awful.
            Call.clientSnapshot(
            lastSent++,
            uid,
            player.dead(),
            player.dead() ? player.x : unit.x, player.dead() ? player.y : unit.y,
            aimPos.x,
            aimPos.y,
            unit.rotation,
            unit instanceof Mechc m ? m.baseRotation() : 0,
            unit.vel.x, unit.vel.y,
            player.unit().mineTile,
            player.boosting, player.shooting, player.typing, control.input.isBuilding,
            player.isBuilder() ? player.unit().plans : null,
            Core.camera.position.x, Core.camera.position.y,
            Core.camera.width, Core.camera.height
            );
            TypeIO.useConfigLocal = false;
        }

        if(timer.get(1, 60)){
            Call.ping(Time.millis());
        }
    }

    String getUsid(String ip){
        //consistently use the latter part of an IP, if possible
        if(ip.contains("/")){
            ip = ip.substring(ip.indexOf("/") + 1);
        }

        if(Core.settings.getString("usid-" + ip, null) != null){
            return Core.settings.getString("usid-" + ip, null);
        }else{
            byte[] bytes = new byte[8];
            new Rand().nextBytes(bytes);
            String result = new String(Base64Coder.encode(bytes));
            Core.settings.put("usid-" + ip, result);
            return result;
        }
    }
}
