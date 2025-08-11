package com.lukarbonite.immersive_serverside.objects;

import com.lukarbonite.immersive_serverside.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Represents the 3D viewing frustum created by looking from an origin point through a rectangular portal.
 * This is used for perspective-correct culling.
 */
public class ViewFrustum {
    private static final double FRUSTUM_CORNER_OFFSET = 0.5;
    private final Vec3d origin;
    // The portal's plane, used for near-plane clipping
    private final Vec3d portalPlaneNormal;
    private final Direction.Axis portalPlaneAxis;
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
        this.portalPlaneAxis = portalPlaneAxis;
        final double playerPlanePos = Util.get(origin, portalPlaneAxis);
        final double portalBlockCoordinate = Util.get(portal.getLowerLeft(), portalPlaneAxis);

        final double frontPlaneCoordinate;
        final double backPlaneCoordinate;

        // Corrected logic: The rendering aperture (frontPlaneCoordinate) should be on the back plane of the portal blocks.
        if (playerPlanePos > portalBlockCoordinate + 0.5) {
            frontPlaneCoordinate = portalBlockCoordinate; // Back plane
            backPlaneCoordinate = portalBlockCoordinate + 1.0; // Front plane
        } else {
            frontPlaneCoordinate = portalBlockCoordinate + 1.0; // Back plane
            backPlaneCoordinate = portalBlockCoordinate; // Front plane
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

    /**
     * Efficiently iterates through every block position within the view frustum.
     * This works by "scan-converting" the frustum volume along its depth axis. For each slice
     * along the depth, it calculates the 2D cross-section of the frustum and iterates only
     * the blocks within that 2D shape. This avoids iterating a large bounding box and
     * performing expensive `contains` checks on every single block.
     *
     * @param consumer The operation to perform on each BlockPos inside the frustum.
     * @param depth The maximum distance to iterate from the portal plane.
     * @param minY The minimum world Y coordinate to consider.
     * @param maxY The maximum world Y coordinate to consider.
     */
    public void iterate(Consumer<BlockPos> consumer, int depth, int minY, int maxY) {
        if (leftPlaneNormal == Vec3d.ZERO) return; // Frustum is collapsed, nothing to iterate.

        Direction.Axis depthAxis = this.portalPlaneAxis;
        Direction.Axis uAxis, vAxis;
        switch (depthAxis) {
            case X -> { uAxis = Direction.Axis.Y; vAxis = Direction.Axis.Z; }
            case Y -> { uAxis = Direction.Axis.X; vAxis = Direction.Axis.Z; }
            default -> { uAxis = Direction.Axis.X; vAxis = Direction.Axis.Y; }
        }

        double originDepth = Util.get(origin, depthAxis);
        double portalOriginDepth = Util.get(portalOrigin, depthAxis);

        int step = originDepth > portalOriginDepth ? -1 : 1;
        int startDepth = MathHelper.floor(portalOriginDepth);
        int endDepth = startDepth + step * depth;

        Vec3d[] rays = new Vec3d[4];
        for (int i = 0; i < 4; i++) {
            rays[i] = frustumBaseCorners[i].subtract(origin);
        }

        BlockPos.Mutable mutablePos = new BlockPos.Mutable();

        for (int d = startDepth; d != endDepth; d += step) {
            if (depthAxis == Direction.Axis.Y && (d < minY || d > maxY)) {
                continue;
            }
            Util.set(mutablePos, d, depthAxis);

            double minU = Double.POSITIVE_INFINITY, maxU = Double.NEGATIVE_INFINITY;
            double minV = Double.POSITIVE_INFINITY, maxV = Double.NEGATIVE_INFINITY;

            // Calculate the 2D bounds of the frustum at this depth slice
            for (Vec3d ray : rays) {
                double rayDepthComponent = Util.get(ray, depthAxis);
                if (Math.abs(rayDepthComponent) < 1e-7) continue;

                double t = (d - originDepth) / rayDepthComponent;
                Vec3d intersect = origin.add(ray.multiply(t));

                minU = Math.min(minU, Util.get(intersect, uAxis));
                maxU = Math.max(maxU, Util.get(intersect, uAxis));
                minV = Math.min(minV, Util.get(intersect, vAxis));
                maxV = Math.max(maxV, Util.get(intersect, vAxis));
            }

            int startU = MathHelper.floor(minU);
            int endU = MathHelper.ceil(maxU);
            int startV = MathHelper.floor(minV);
            int endV = MathHelper.ceil(maxV);

            // Corrected Clamping Logic
            int clampedStartU = (uAxis == Direction.Axis.Y) ? Math.max(startU, minY) : startU;
            int clampedEndU = (uAxis == Direction.Axis.Y) ? Math.min(endU, maxY + 1) : endU;
            int clampedStartV = (vAxis == Direction.Axis.Y) ? Math.max(startV, minY) : startV;
            int clampedEndV = (vAxis == Direction.Axis.Y) ? Math.min(endV, maxY + 1) : endV;

            // Iterate within the calculated 2D bounds for this slice
            for (int u = clampedStartU; u < clampedEndU; u++) {
                Util.set(mutablePos, u, uAxis);
                for (int v = clampedStartV; v < clampedEndV; v++) {
                    Util.set(mutablePos, v, vAxis);
                    consumer.accept(mutablePos);
                }
            }
        }
    }

    public Vec3d[] getFrustumBaseCorners() {
        return this.frustumBaseCorners;
    }

    public Vec3d getPortalPlaneNormal() {
        return this.portalPlaneNormal;
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