package nl.theepicblock.immersive_cursedness.objects;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import nl.theepicblock.immersive_cursedness.Util;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * Represents the 3D viewing frustum created by looking from an origin point through a rectangular portal.
 * This is used for perspective-correct culling.
 */
public class ViewFrustum {
    private final Vec3d origin;
    // The portal's plane, used for near-plane clipping
    private final Vec3d portalPlaneNormal;
    private final Vec3d portalOrigin;
    // The four planes that define the sides of the frustum.
    // The normal vector of each plane points *outwards* from the frustum volume.
    private final Vec3d topPlaneNormal;
    private final Vec3d bottomPlaneNormal;
    private final Vec3d leftPlaneNormal;
    private final Vec3d rightPlaneNormal;

    private final Vec3d[] frustumBaseCorners;

    public ViewFrustum(Vec3d origin, Portal portal) {
        this.origin = origin;

        // 1. Determine the portal's plane axis (the normal to the portal face) and player's position on it.
        Direction.Axis portalPlaneAxis = Util.rotate(portal.getAxis());
        double playerPlanePos = Util.get(origin, portalPlaneAxis);

        // 2. Get the portal's min/max coordinates on that plane axis.
        double portalMinPlanePos = Util.get(portal.getLowerLeft(), portalPlaneAxis);
        double portalMaxPlanePos = Util.get(portal.getUpperRight(), portalPlaneAxis);

        // 3. Determine the coordinate of the near plane, which should be the face FARTHEST from the player.
        // A block at `c` occupies the volume from `c` to `c+1`.
        // The "min" face of the entire portal volume is at `portalMinPlanePos`.
        // The "max" face of the entire portal volume is at `portalMaxPlanePos + 1.0`.
        double portalFrontCoordinate;
        if (Math.abs(playerPlanePos - portalMinPlanePos) > Math.abs(playerPlanePos - (portalMaxPlanePos + 1.0))) {
            // The player is farther from the min-side face, so we use that one.
            portalFrontCoordinate = portalMinPlanePos - 1.0;
        } else {
            // The player is farther from the max-side face, so we use that one.
            portalFrontCoordinate = portalMaxPlanePos + 2.0;
        }

        // 4. Create the front rectangle definition for the frustum using this dynamic coordinate.
        final FlatStandingRectangle portalRectFront = new FlatStandingRectangle(
                portal.getTop() + 1.5,
                portal.getBottom() - 0.5,
                portal.getLeft() - 0.5,
                portal.getRight() + 1.5,
                portalFrontCoordinate,
                portalPlaneAxis
        );

        // The rest of the constructor logic can now proceed with this corrected `portalRectFront`.
        double portalToPlayerSign = Math.signum(playerPlanePos - portalFrontCoordinate);

        if (portalToPlayerSign == 0) { // Player is on the plane, frustum has no volume.
            this.portalOrigin = Vec3d.ZERO;
            this.portalPlaneNormal = Vec3d.ZERO;
            this.topPlaneNormal = Vec3d.ZERO;
            this.bottomPlaneNormal = Vec3d.ZERO;
            this.leftPlaneNormal = Vec3d.ZERO;
            this.rightPlaneNormal = Vec3d.ZERO;
            this.frustumBaseCorners = new Vec3d[4];
            Arrays.fill(this.frustumBaseCorners, origin); // Collapse to a point
            return;
        }

        double portalBackCoordinate = portalFrontCoordinate - portalToPlayerSign;
        final FlatStandingRectangle portalRectBack = portalRectFront.expandAbsolute(portalBackCoordinate, origin);

        // Vertices of the portal's front and back rectangles
        final Vec3d tl_f = portalRectFront.getTopLeft();
        final Vec3d tr_f = portalRectFront.getTopRight();
        final Vec3d bl_f = portalRectFront.getBottomLeft();
        final Vec3d br_f = portalRectFront.getBottomRight();

        final Vec3d tl_b = portalRectBack.getTopLeft();
        final Vec3d tr_b = portalRectBack.getTopRight();
        final Vec3d bl_b = portalRectBack.getBottomLeft();
        final Vec3d br_b = portalRectBack.getBottomRight();

        // Calculate the effective corners of the frustum base (a trapezoid on the back plane)
        Direction.Axis primaryAxis = Util.rotate(portalPlaneAxis);
        double playerPrimaryCoord = Util.get(origin, primaryAxis);
        double portalCenterPrimaryCoord = (portalRectFront.getLeft() + portalRectFront.getRight()) / 2.0;

        final Vec3d final_tl, final_tr, final_bl, final_br;

        if (playerPrimaryCoord < portalCenterPrimaryCoord) { // Player is on the "left" side
            final_tl = tl_f;
            final_bl = bl_f;
            final_tr = tr_b;
            final_br = br_b;
        } else { // Player is on the "right"
            final_tr = tr_f;
            final_br = br_f;
            final_tl = tl_b;
            final_bl = bl_b;
        }
        this.frustumBaseCorners = new Vec3d[]{final_tl, final_tr, final_bl, final_br};

        // Check for crossover (player looking at the portal from an extreme side angle)
        Vec3d view_to_final_tl = final_tl.subtract(origin);
        Vec3d view_to_final_tr = final_tr.subtract(origin);
        double v_tl_primary = Util.get(view_to_final_tl, primaryAxis);
        double v_tl_plane = Util.get(view_to_final_tl, portalPlaneAxis);
        double v_tr_primary = Util.get(view_to_final_tr, primaryAxis);
        double v_tr_plane = Util.get(view_to_final_tr, portalPlaneAxis);
        double crossProduct = v_tr_primary * v_tl_plane - v_tr_plane * v_tl_primary;

        if (crossProduct * portalToPlayerSign > 0) { // Crossover detected
            Vec3d collapsePoint = final_tl.add(final_tr).multiply(0.5);
            this.portalOrigin = collapsePoint;
            this.portalPlaneNormal = origin.subtract(collapsePoint).normalize();
            this.topPlaneNormal = this.bottomPlaneNormal = this.leftPlaneNormal = this.rightPlaneNormal = Vec3d.ZERO;
            return;
        }

        // Define planes using the robustly calculated corners
        Vec3d otl = final_tl.subtract(origin);
        Vec3d otr = final_tr.subtract(origin);
        Vec3d obl = final_bl.subtract(origin);
        Vec3d obr = final_br.subtract(origin);

        Vec3d centerVec = portalRectFront.getCenter().subtract(origin);

        Vec3d tn = otr.crossProduct(otl).normalize();
        this.topPlaneNormal = centerVec.dotProduct(tn) > 0 ? tn.multiply(-1) : tn;

        Vec3d bn = obl.crossProduct(obr).normalize();
        this.bottomPlaneNormal = centerVec.dotProduct(bn) > 0 ? bn.multiply(-1) : bn;

        Vec3d ln = otl.crossProduct(obl).normalize();
        this.leftPlaneNormal = centerVec.dotProduct(ln) > 0 ? ln.multiply(-1) : ln;

        Vec3d rn = obr.crossProduct(otr).normalize();
        this.rightPlaneNormal = centerVec.dotProduct(rn) > 0 ? rn.multiply(-1) : rn;

        // Near clipping plane is the portal's front face
        this.portalOrigin = tl_f;
        Vector3f unitVector = Direction.get(Direction.AxisDirection.POSITIVE, portalRectFront.getAxis()).getUnitVector();
        this.portalPlaneNormal = new Vec3d(unitVector.x(), unitVector.y(), unitVector.z()).multiply(portalToPlayerSign);
    }

    public Vec3d getPortalPlaneNormal() {
        return this.portalPlaneNormal;
    }

    public Box getIterationBox(int depth) {
        // Start with the player's eye position
        double minX = origin.x, minY = origin.y, minZ = origin.z;
        double maxX = origin.x, maxY = origin.y, maxZ = origin.z;

        // Get the four corners of the projected base on the far plane
        Vec3d[] farCorners = new Vec3d[4];
        for (int i=0; i<4; i++) {
            Vec3d dir = frustumBaseCorners[i].subtract(origin);
            if (dir.lengthSquared() > 1e-7) {
                farCorners[i] = origin.add(dir.normalize().multiply(depth));
            } else {
                farCorners[i] = frustumBaseCorners[i]; // If a corner is at the eye, use that
            }
        }

        // Expand the box to include the far corners
        for (Vec3d v : farCorners) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
            maxZ = Math.max(maxZ, v.z);
        }

        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Checks if a given BlockPos is inside this view frustum.
     * This is done by checking if any of the block's 8 corners are within the frustum.
     * @param pos The position of the block to check.
     * @return true if any part of the block is inside the frustum, false otherwise.
     */
    public boolean contains(BlockPos pos) {
        Vec3d[] corners = new Vec3d[8];
        corners[0] = new Vec3d(pos.getX(),     pos.getY(),     pos.getZ());
        corners[1] = new Vec3d(pos.getX() + 1, pos.getY(),     pos.getZ());
        corners[2] = new Vec3d(pos.getX(),     pos.getY() + 1, pos.getZ());
        corners[3] = new Vec3d(pos.getX(),     pos.getY(),     pos.getZ() + 1);
        corners[4] = new Vec3d(pos.getX() + 1, pos.getY() + 1, pos.getZ());
        corners[5] = new Vec3d(pos.getX() + 1, pos.getY(),     pos.getZ() + 1);
        corners[6] = new Vec3d(pos.getX(),     pos.getY() + 1, pos.getZ() + 1);
        corners[7] = new Vec3d(pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);

        for (Vec3d corner : corners) {
            if (contains(corner)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if a given Vec3d position is inside this view frustum.
     * A point is "inside" if it is on the "inner" side of all five bounding planes.
     * @param pos The position to check.
     * @return true if the position is inside the frustum, false otherwise.
     */
    public boolean contains(Vec3d pos) {
        if (leftPlaneNormal == Vec3d.ZERO) return false; // Frustum is collapsed

        if (!containsInSidePlanes(pos)) {
            return false;
        }

        // Check the near plane (the portal itself)
        // A point must be on the opposite side of the portal plane from the player.
        return pos.subtract(this.portalOrigin).dotProduct(this.portalPlaneNormal) <= 0;
    }

    /**
     * Checks if a given Vec3d position is inside the four side planes of the frustum, ignoring the near plane.
     * This is useful for culling objects on the same side of the portal as the player.
     * @param pos The position to check.
     * @return true if the position is within the side planes, false otherwise.
     */
    public boolean containsInSidePlanes(Vec3d pos) {
        Vec3d vectorToPoint = pos.subtract(origin);

        // Check the four side planes
        if (vectorToPoint.dotProduct(this.topPlaneNormal) > 0) return false;
        if (vectorToPoint.dotProduct(this.bottomPlaneNormal) > 0) return false;
        if (vectorToPoint.dotProduct(this.leftPlaneNormal) > 0) return false;
        if (vectorToPoint.dotProduct(this.rightPlaneNormal) > 0) return false;

        return true;
    }
}