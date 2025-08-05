package com.lukarbonite.immersive_serverside.objects;

import com.lukarbonite.immersive_serverside.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * Represents the 3D viewing frustum created by looking from an origin point through a rectangular portal.
 * This is used for perspective-correct culling.
 */
public class ViewFrustum {
    private static final double FRUSTUM_CORNER_OFFSET = 0.5;
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

    // Fields for atmosphere box calculation
    private final Vec3d portalCenter;
    private final double atmosphereRadius;

    public ViewFrustum(Vec3d origin, Portal portal, double atmosphereRadius) {
        this.origin = origin;
        this.atmosphereRadius = atmosphereRadius;

        final Direction.Axis portalPlaneAxis = Util.rotate(portal.getAxis());
        final double playerPlanePos = Util.get(origin, portalPlaneAxis);
        final double portalBlockCoordinate = Util.get(portal.getLowerLeft(), portalPlaneAxis);

        final double frontPlaneCoordinate;
        final double backPlaneCoordinate;

        if (playerPlanePos > portalBlockCoordinate + 0.5) {
            frontPlaneCoordinate = portalBlockCoordinate + 1.0;
            backPlaneCoordinate = portalBlockCoordinate;
        } else {
            frontPlaneCoordinate = portalBlockCoordinate;
            backPlaneCoordinate = portalBlockCoordinate + 1.0;
        }

        final FlatStandingRectangle portalAperture = new FlatStandingRectangle(
                portal.getTop() + 1.0 + FRUSTUM_CORNER_OFFSET,
                portal.getBottom() - FRUSTUM_CORNER_OFFSET,
                portal.getLeft() - FRUSTUM_CORNER_OFFSET,
                portal.getRight() + 1.0 + FRUSTUM_CORNER_OFFSET,
                frontPlaneCoordinate, portalPlaneAxis
        );
        this.portalCenter = portalAperture.getCenter(); // Store the portal center

        final FlatStandingRectangle backPlane = new FlatStandingRectangle(
                portalAperture.getTop(), portalAperture.getBottom(),
                portalAperture.getLeft(), portalAperture.getRight(),
                backPlaneCoordinate, portalPlaneAxis
        );

        final FlatStandingRectangle projectedView = backPlane.expandAbsolute(frontPlaneCoordinate, origin);

        final double clippedLeft = Math.max(portalAperture.getLeft(), projectedView.getLeft());
        final double clippedRight = Math.min(portalAperture.getRight(), projectedView.getRight());
        final double clippedTop = Math.min(portalAperture.getTop(), projectedView.getTop());
        final double clippedBottom = Math.max(portalAperture.getBottom(), projectedView.getBottom());

        if (clippedLeft >= clippedRight || clippedBottom >= clippedTop) {
            Vec3d collapsePoint = portalAperture.getCenter();
            this.portalOrigin = collapsePoint;
            this.portalPlaneNormal = origin.subtract(collapsePoint).normalize();
            this.topPlaneNormal = this.bottomPlaneNormal = this.leftPlaneNormal = this.rightPlaneNormal = Vec3d.ZERO;
            this.frustumBaseCorners = new Vec3d[4];
            Arrays.fill(this.frustumBaseCorners, origin);
            return;
        }

        final FlatStandingRectangle clippedFrontRect = new FlatStandingRectangle(clippedTop, clippedBottom, clippedLeft, clippedRight, frontPlaneCoordinate, portalPlaneAxis);
        final FlatStandingRectangle finalFrustumBase = clippedFrontRect.expandAbsolute(backPlaneCoordinate, origin);

        final Vec3d final_tl = finalFrustumBase.getTopLeft();
        final Vec3d final_tr = finalFrustumBase.getTopRight();
        final Vec3d final_bl = finalFrustumBase.getBottomLeft();
        final Vec3d final_br = finalFrustumBase.getBottomRight();

        this.frustumBaseCorners = new Vec3d[]{final_tl, final_tr, final_bl, final_br};

        Vec3d centerVec = finalFrustumBase.getCenter().subtract(origin);

        Vec3d otl = final_tl.subtract(origin);
        Vec3d otr = final_tr.subtract(origin);
        Vec3d obl = final_bl.subtract(origin);
        Vec3d obr = final_br.subtract(origin);

        Vec3d tn = otr.crossProduct(otl).normalize();
        this.topPlaneNormal = centerVec.dotProduct(tn) > 0 ? tn.multiply(-1) : tn;

        Vec3d bn = obl.crossProduct(obr).normalize();
        this.bottomPlaneNormal = centerVec.dotProduct(bn) > 0 ? bn.multiply(-1) : bn;

        Vec3d ln = otl.crossProduct(obl).normalize();
        this.leftPlaneNormal = centerVec.dotProduct(ln) > 0 ? ln.multiply(-1) : ln;

        Vec3d rn = obr.crossProduct(otr).normalize();
        this.rightPlaneNormal = centerVec.dotProduct(rn) > 0 ? rn.multiply(-1) : rn;

        this.portalOrigin = portalAperture.getTopLeft();
        double portalToPlayerSign = Math.signum(playerPlanePos - frontPlaneCoordinate);
        Vector3f unitVector = Direction.get(Direction.AxisDirection.POSITIVE, portalAperture.getAxis()).getUnitVector();
        this.portalPlaneNormal = new Vec3d(unitVector.x(), unitVector.y(), unitVector.z()).multiply(portalToPlayerSign);
    }

    public ViewFrustum(Vec3d origin, Portal portal) {
        this(origin, portal, 0); // Keep old constructor for compatibility.
    }

    public Vec3d[] getFrustumBaseCorners() {
        return this.frustumBaseCorners;
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

        Box frustumBox = new Box(minX, minY, minZ, maxX, maxY, maxZ);

        // Also include the atmosphere sphere in the iteration box
        if (this.atmosphereRadius > 0 && this.portalCenter != null) {
            Box atmosphereBox = new Box(this.portalCenter, this.portalCenter).expand(this.atmosphereRadius);
            return frustumBox.union(atmosphereBox).expand(1.0);
        }

        return frustumBox.expand(1.0);
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