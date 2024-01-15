package mindustry.client.navigation.waypoints;

import arc.math.geom.*;

/**
 * A way of representing a waypoint.  You're probably looking for {@link PositionWaypoint}
 */
public abstract class Waypoint implements Position {

    /** Returns if the waypoint is finished. */
    public abstract boolean isDone();

    /** This is run each iteration of the navigation following loop. */
    public abstract Waypoint run();

    /** Draws the waypoint. */
    public abstract void draw();

    /** Run once upon finishing. */
    public void onFinish() {}
}
