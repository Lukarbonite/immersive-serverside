package nl.theepicblock.immersive_cursedness.objects;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import nl.theepicblock.immersive_cursedness.Util;

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
    private final Vec3d[] frustumCorners;

    public ViewFrustum(Vec3d origin, Portal portal) {
        this.origin = origin;

        FlatStandingRectangle portalRectFront = portal.toFlatStandingRectangle();
        Direction.Axis planeAxis = portalRectFront.getAxis();

        // Determine front and back planes
        double portalFrontPlaneCoord = portalRectFront.getOther();
        double playerPlaneCoord = Util.get(origin, planeAxis);

        double portalCenterPlaneCoord = portalFrontPlaneCoord + 0.5;
        double backPlaneCoord;
        if (playerPlaneCoord > portalCenterPlaneCoord) {
            backPlaneCoord = portalFrontPlaneCoord;
        } else {
            backPlaneCoord = portalFrontPlaneCoord + 1.0;
        }


        // Get corners of the portal rectangle (the hole)
        double bottomY = portal.getBottom();
        double topY = portal.getTop() + 1.0;
        double leftContent = portal.getLeft();
        double rightContent = portal.getRight() + 1.0;

        // Calculate the four corners of the back plane
        Vec3d btl, btr, bbl, bbr;
        if (planeAxis == Direction.Axis.X) {
            btl = new Vec3d(backPlaneCoord, topY, leftContent);
            btr = new Vec3d(backPlaneCoord, topY, rightContent);
            bbl = new Vec3d(backPlaneCoord, bottomY, leftContent);
            bbr = new Vec3d(backPlaneCoord, bottomY, rightContent);
        } else { // planeAxis is Z
            btl = new Vec3d(leftContent, topY, backPlaneCoord);
            btr = new Vec3d(rightContent, topY, backPlaneCoord);
            bbl = new Vec3d(leftContent, bottomY, backPlaneCoord);
            bbr = new Vec3d(rightContent, bottomY, backPlaneCoord);
        }

        this.frustumCorners = new Vec3d[]{btl, btr, bbl, bbr};

        // Vectors from origin to the BACK corners define the side planes
        Vec3d obtl = btl.subtract(origin);
        Vec3d obtr = btr.subtract(origin);
        Vec3d obbl = bbl.subtract(origin);
        Vec3d obbr = bbr.subtract(origin);

        // A point guaranteed to be "inside" the frustum side planes is the center of the portal rect itself.
        Vec3d frustumCenterVector = portalRectFront.getCenter().subtract(origin);

        Vec3d tn = obtr.crossProduct(obtl).normalize();
        if (frustumCenterVector.dotProduct(tn) > 0) tn = tn.multiply(-1);
        this.topPlaneNormal = tn;

        Vec3d bn = obbl.crossProduct(obbr).normalize();
        if (frustumCenterVector.dotProduct(bn) > 0) bn = bn.multiply(-1);
        this.bottomPlaneNormal = bn;

        Vec3d ln = obtl.crossProduct(obbl).normalize();
        if (frustumCenterVector.dotProduct(ln) > 0) ln = ln.multiply(-1);
        this.leftPlaneNormal = ln;

        Vec3d rn = obbr.crossProduct(obtr).normalize();
        if (frustumCenterVector.dotProduct(rn) > 0) rn = rn.multiply(-1);
        this.rightPlaneNormal = rn;

        // Define the near-clipping plane using the FRONT face of the portal
        this.portalOrigin = portalRectFront.getTopLeft();
        Vec3d v1 = portalRectFront.getTopRight().subtract(portalOrigin);
        Vec3d v2 = portalRectFront.getBottomLeft().subtract(portalOrigin);
        Vec3d normal = v2.crossProduct(v1).normalize();
        if (origin.subtract(portalOrigin).dotProduct(normal) < 0) {
            normal = normal.multiply(-1);
        }
        this.portalPlaneNormal = normal;
    }

    public Vec3d getPortalPlaneNormal() {
        return portalPlaneNormal;
    }

    public Box getIterationBox(int depth) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE, maxZ = Double.MIN_VALUE;

        // The 8 vertices of the frustum volume (4 near, 4 far)
        Vec3d[] vertices = new Vec3d[8];
        for (int i = 0; i < 4; i++) {
            Vec3d nearCorner = this.frustumCorners[i];
            Vec3d dir = nearCorner.subtract(this.origin).normalize();
            Vec3d farCorner = nearCorner.add(dir.multiply(depth));
            vertices[i] = nearCorner;
            vertices[i+4] = farCorner;
        }

        for (Vec3d v : vertices) {
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