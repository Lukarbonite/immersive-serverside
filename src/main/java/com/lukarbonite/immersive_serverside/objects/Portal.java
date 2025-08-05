package com.lukarbonite.immersive_serverside.objects;

import com.lukarbonite.immersive_serverside.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class Portal {
    //Right is defined as the most positive point in whatever axis this is
    private final BlockPos upperRight;
    private final BlockPos lowerLeft;
    private final Direction.Axis axis;
    private final boolean hasCorners;
    private final TransformProfile transformProfile;

    public Portal(BlockPos upperRight, BlockPos lowerLeft, Direction.Axis axis, boolean hasCorners, TransformProfile transformProfile) {
        this.upperRight = upperRight;
        this.lowerLeft = lowerLeft;
        this.axis = axis;
        this.hasCorners = hasCorners;
        this.transformProfile = transformProfile;
    }

    public boolean hasCorners() {
        return this.hasCorners;
    }

    public double getDistance(BlockPos pos) {
        return upperRight.getSquaredDistance(pos);
    }

    /**
     * Returns true if this rectangle fully encloses b
     */
    public boolean contains(Portal b) {
        if (this.getAxis() != b.getAxis()) return false;
        if (this.getTop() < b.getTop() ||
                this.getBottom() > b.getBottom()) return false;
        Direction.Axis axis = this.getAxis();
        return this.getRight() >= b.getRight() &&
                this.getLeft() <= b.getLeft();
    }

    public boolean isBlockposBehind(BlockPos p, Vec3d originContext) {
        return isPosBehind(p.toCenterPos(), originContext);
    }

    public boolean isPosBehind(Vec3d p, Vec3d originContext) {
        if (!isBehind(originContext, p)) return false;
        FlatStandingRectangle rect = this.toFlatStandingRectangle();
        FlatStandingRectangle rect2 = rect.expandAbsolute(Util.get(p, rect.axis), originContext);
        return rect2.contains(p);
    }

    private boolean isBehind(Vec3d origin, BlockPos p) {
        return isBehind(origin, p.toCenterPos());
    }

    private boolean isBehind(Vec3d origin, Vec3d p) {
        Direction.Axis rot = Util.rotate(axis);
        double a = Util.get(p, rot);
        double b = Util.get(origin, rot);
        double middle = Util.get(upperRight, rot);
        return a < middle ^ middle > b;
    }

    public BlockPos getUpperRight() {
        return upperRight;
    }

    public BlockPos getLowerLeft() {
        return lowerLeft;
    }

    public Direction.Axis getAxis() {
        return axis;
    }

    public TransformProfile getTransformProfile() {
        return transformProfile;
    }

    public int getLeft() {
        return Util.get(this.getLowerLeft(), this.getAxis());
    }

    public int getRight() {
        return Util.get(this.getUpperRight(), this.getAxis());
    }

    public int getTop() {
        return this.getUpperRight().getY();
    }

    public int getBottom() {
        return this.getLowerLeft().getY();
    }

    public FlatStandingRectangle toFlatStandingRectangle() {
        return new FlatStandingRectangle(
                this.getTop() + 1.5,
                this.getBottom() - 0.5,
                this.getLeft() - 0.5,
                this.getRight() + 1.5,
                Util.get(this.getUpperRight(), Util.rotate(axis)),
                Util.rotate(axis)
        );
    }

    public FlatStandingRectangle getFrustumShape(Vec3d playerEyePos) {
        final FlatStandingRectangle portalRectFront = this.toFlatStandingRectangle();

        Direction.Axis portalPlaneAxis = portalRectFront.getAxis();
        double portalFrontCoordinate = portalRectFront.getOther();
        double playerDepth = Util.get(playerEyePos, portalPlaneAxis);
        double portalToPlayerSign = Math.signum(playerDepth - portalFrontCoordinate);

        // This check prevents visual artifacts when player is perfectly aligned with the portal plane.
        if (portalToPlayerSign == 0) {
            final Vec3d collapsePoint = portalRectFront.getCenter();
            return new FlatStandingRectangle(0,0,0,0,0,portalPlaneAxis) {
                @Override public Vec3d getTopLeft() { return collapsePoint; }
                @Override public Vec3d getTopRight() { return collapsePoint; }
                @Override public Vec3d getBottomLeft() { return collapsePoint; }
                @Override public Vec3d getBottomRight() { return collapsePoint; }
            };
        }

        double portalBackCoordinate = portalFrontCoordinate - portalToPlayerSign;
        final FlatStandingRectangle portalRectBack = portalRectFront.expandAbsolute(portalBackCoordinate, playerEyePos);

        final Vec3d tl_f = portalRectFront.getTopLeft();
        final Vec3d tr_f = portalRectFront.getTopRight();
        final Vec3d bl_f = portalRectFront.getBottomLeft();
        final Vec3d br_f = portalRectFront.getBottomRight();

        final Vec3d tl_b = portalRectBack.getTopLeft();
        final Vec3d tr_b = portalRectBack.getTopRight();
        final Vec3d bl_b = portalRectBack.getBottomLeft();
        final Vec3d br_b = portalRectBack.getBottomRight();

        Direction.Axis primaryAxis = Util.rotate(portalPlaneAxis);
        double playerPrimaryCoord = Util.get(playerEyePos, primaryAxis);
        double portalCenterPrimaryCoord = (portalRectFront.left + portalRectFront.right) / 2.0;

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

        // Robust check for viewing angle crossover
        Vec3d view_to_final_tl = final_tl.subtract(playerEyePos);
        Vec3d view_to_final_tr = final_tr.subtract(playerEyePos);

        double v_tl_primary = Util.get(view_to_final_tl, primaryAxis);
        double v_tl_plane = Util.get(view_to_final_tl, portalPlaneAxis);
        double v_tr_primary = Util.get(view_to_final_tr, primaryAxis);
        double v_tr_plane = Util.get(view_to_final_tr, portalPlaneAxis);

        // 2D cross product of the horizontal components of the view vectors
        double crossProduct = v_tr_primary * v_tl_plane - v_tr_plane * v_tl_primary;

        // If the cross product sign indicates the view has inverted, collapse the frustum
        if (crossProduct * portalToPlayerSign > 0) {
            final Vec3d collapsePoint = final_tl.add(final_tr).multiply(0.5);
            return new FlatStandingRectangle(0,0,0,0,0,portalPlaneAxis) {
                @Override public Vec3d getTopLeft() { return collapsePoint; }
                @Override public Vec3d getTopRight() { return collapsePoint; }
                @Override public Vec3d getBottomLeft() { return collapsePoint; }
                @Override public Vec3d getBottomRight() { return collapsePoint; }
            };
        }

        return new FlatStandingRectangle(
                portalRectFront.top, portalRectFront.bottom, portalRectFront.left, portalRectFront.right,
                portalRectFront.other, portalRectFront.axis
        ) {
            @Override public Vec3d getTopLeft() { return final_tl; }
            @Override public Vec3d getTopRight() { return final_tr; }
            @Override public Vec3d getBottomLeft() { return final_bl; }
            @Override public Vec3d getBottomRight() { return final_br; }
            @Override public Vec3d getCenter() { return final_tl.add(final_tr).add(final_bl).add(final_br).multiply(0.25); }
        };
    }

    public int getYawRelativeTo(BlockPos pos) {
        if (this.axis == Direction.Axis.Z) {
            if (pos.getX()-this.lowerLeft.getX()<0) {
                return -90;
            } else {
                return 90;
            }
        } else {
            if (pos.getZ()-this.lowerLeft.getZ()<0) {
                return 0;
            } else {
                return 180;
            }
        }
    }

    public boolean isCloserThan(Vec3d p, int i) {
        double lrp = Util.get(p, axis);
        double lrmin = Util.get(lowerLeft, axis);
        double lrmax = Util.get(upperRight, axis)+1;
        double lrd;
        if (lrp > lrmax) {
            lrd = lrp-lrmax;
        } else if (lrp < lrmin) {
            lrd = lrmin-lrp;
        } else {
            lrd = 0;
        }
        if (lrd > i) return false;

        double yp = p.y;
        double ymin = lowerLeft.getY();
        double ymax = upperRight.getY()+1;
        double yd;
        if (yp > ymax) {
            yd = yp-ymax;
        } else if (yp < ymin) {
            yd = ymin-yp;
        } else {
            yd = 0;
        }
        if (yd > i) return false;

        Direction.Axis other = Util.rotate(axis);
        double od = Math.abs(Util.get(p, other)-Util.get(lowerLeft,other));
        if (od > i) return false;
        return (lrd*lrd+yd*yd+od*od)<i*i;
    }
}