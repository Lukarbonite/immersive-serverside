package nl.theepicblock.immersive_cursedness.objects;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class TransformProfile {
    private final int originalX;
    private final int originalY;
    private final int originalZ;
    private final int targetX;
    private final int targetY;
    private final int targetZ;
    private final int rotation;

    public TransformProfile(BlockPos original, BlockPos target, int originalRot, int targetRot) {
        this.originalX = original.getX();
        this.originalY = original.getY();
        this.originalZ = original.getZ();
        this.targetX = target.getX();
        this.targetY = target.getY();
        this.targetZ = target.getZ();

        int rotation = targetRot-originalRot;
        if (rotation == -270) rotation = 90;
        if (rotation == 270) rotation = -90;
        if (rotation == -180) rotation = 180;
        this.rotation = rotation;
    }

    public int transformYOnly(int y) {
        return y-originalY+targetY;
    }

    public int unTransformYOnly(int y) {
        return y-targetY+originalY;
    }

    public BlockPos transform(BlockPos in) {
        int relX = in.getX() - originalX;
        int relZ = in.getZ() - originalZ;

        int newRelX;
        int newRelZ;

        switch (this.rotation) {
            case 90:
                newRelX = -relZ;
                newRelZ = relX;
                break;
            case -90:
                newRelX = relZ;
                newRelZ = -relX;
                break;
            case 180:
                newRelX = -relX;
                newRelZ = -relZ;
                break;
            default:
                newRelX = relX;
                newRelZ = relZ;
                break;
        }

        return new BlockPos(
                newRelX + targetX,
                (in.getY() - originalY) + targetY,
                newRelZ + targetZ
        );
    }

    public Vec3d transform(Vec3d in) {
        double relX = in.getX() - originalX;
        double relZ = in.getZ() - originalZ;

        double newRelX;
        double newRelZ;

        switch (this.rotation) {
            case 90: // CCW
                newRelX = -relZ;
                newRelZ = relX;
                break;
            case -90: // CW
                newRelX = relZ;
                newRelZ = -relX;
                break;
            case 180:
                newRelX = -relX;
                newRelZ = -relZ;
                break;
            default: // 0 degrees
                newRelX = relX;
                newRelZ = relZ;
                break;
        }

        return new Vec3d(
                newRelX + targetX,
                (in.getY() - originalY) + targetY,
                newRelZ + targetZ
        );
    }

    public BlockState rotateState(BlockState in) {
        return switch (rotation) {
            default -> in;
            case 90 -> in.rotate(BlockRotation.COUNTERCLOCKWISE_90);
            case -90 -> in.rotate(BlockRotation.CLOCKWISE_90);
            case 180 -> in.rotate(BlockRotation.CLOCKWISE_180);
        };
    }

    public Direction rotate(Direction in) {
        return switch (in) {
            default -> in;
            case NORTH -> switch (this.rotation) {
                default -> in;
                case 90 -> Direction.WEST;
                case -90 -> Direction.EAST;
                case 180 -> Direction.SOUTH;
            };
            case WEST -> switch (this.rotation) {
                default -> in;
                case 90 -> Direction.SOUTH;
                case -90 -> Direction.NORTH;
                case 180 -> Direction.EAST;
            };
            case EAST -> switch (this.rotation) {
                default -> in;
                case 90 -> Direction.NORTH;
                case -90 -> Direction.SOUTH;
                case 180 -> Direction.WEST;
            };
            case SOUTH -> switch (this.rotation) {
                default -> in;
                case 90 -> Direction.EAST;
                case -90 -> Direction.WEST;
                case 180 -> Direction.NORTH;
            };
        };
    }

    public BlockState transformAndGetFromWorld(BlockPos pos, AsyncWorldView world) {
        BlockPos transformedPos = this.transform(pos);
        BlockState state = world.getBlock(transformedPos);
        return this.rotateState(state);
    }

    public BlockEntity transformAndGetFromWorldBlockEntity(BlockPos pos, AsyncWorldView world) {
        BlockPos transformedPos = this.transform(pos);
        return world.getBlockEntity(transformedPos);
    }
}