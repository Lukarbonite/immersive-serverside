package nl.theepicblock.immersive_cursedness.objects;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

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

    public ViewFrustum(Vec3d origin, FlatStandingRectangle portalRect) {
        this.origin = origin;

        Vec3d tl = portalRect.getTopLeft();
        Vec3d tr = portalRect.getTopRight();
        Vec3d bl = portalRect.getBottomLeft();
        Vec3d br = portalRect.getBottomRight();

        // The vectors from the origin to the portal corners
        Vec3d otl = tl.subtract(origin);
        Vec3d otr = tr.subtract(origin);
        Vec3d obl = bl.subtract(origin);
        Vec3d obr = br.subtract(origin);

        // A point guaranteed to be "inside" the frustum side planes is the center of the portal rect itself.
        // We use the vector from the origin to this point to check if the normals are pointing outwards.
        // An outward-pointing normal should have a negative dot product with this vector.
        Vec3d frustumCenterVector = portalRect.getCenter().subtract(origin);

        Vec3d tn = otr.crossProduct(otl).normalize();
        if (frustumCenterVector.dotProduct(tn) > 0) tn = tn.multiply(-1);
        this.topPlaneNormal = tn;

        Vec3d bn = obl.crossProduct(obr).normalize();
        if (frustumCenterVector.dotProduct(bn) > 0) bn = bn.multiply(-1);
        this.bottomPlaneNormal = bn;

        Vec3d ln = otl.crossProduct(obl).normalize();
        if (frustumCenterVector.dotProduct(ln) > 0) ln = ln.multiply(-1);
        this.leftPlaneNormal = ln;

        Vec3d rn = obr.crossProduct(otr).normalize();
        if (frustumCenterVector.dotProduct(rn) > 0) rn = rn.multiply(-1);
        this.rightPlaneNormal = rn;

        // Define the portal plane for near-clipping
        this.portalOrigin = tl;
        Vec3d v1 = tr.subtract(tl);
        Vec3d v2 = bl.subtract(tl);
        // The portal normal points from the portal towards the player
        Vec3d normal = v2.crossProduct(v1).normalize();
        if (origin.subtract(portalOrigin).dotProduct(normal) < 0) {
            normal = normal.multiply(-1);
        }
        this.portalPlaneNormal = normal;
    }

    public Vec3d getPortalPlaneNormal() {
        return portalPlaneNormal;
    }

    /**
     * Checks if a given BlockPos is inside this view frustum.
     * @param pos The position of the block to check.
     * @return true if the block is inside the frustum, false otherwise.
     */
    public boolean contains(BlockPos pos) {
        return contains(pos.toCenterPos());
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
        return pos.subtract(this.portalOrigin).dotProduct(this.portalPlaneNormal) < 0;
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