package com.maximpolyakov.quicklink.neoforge.client;

import com.maximpolyakov.quicklink.QuickLinkColors;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import com.maximpolyakov.quicklink.neoforge.blockentity.ItemPlugBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import org.joml.Matrix4f;

public class ItemPlugBlockEntityRenderer implements BlockEntityRenderer<ItemPlugBlockEntity> {

    private static final float EPS = 0.001f;

    // White texture from block atlas; we tint it.
    private static final ResourceLocation WHITE_TEX =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block/white_wool");

    public ItemPlugBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(ItemPlugBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {

        // ===== DIAGNOSTIC SWITCHES =====
        final boolean DBG_LOG_EVERY_2S = false;          // лог в консоль раз в ~2 секунды
        final boolean DBG_FORCE_FULLBRIGHT = false;     // если true — всегда ярко (для отладки)
        final boolean DBG_DRAW_ALL_FACES = true;       // оставь false: рисуем только на be.getSide()
        // =================================

        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS));

        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(ResourceLocation.fromNamespaceAndPath("minecraft", "block/white_wool"));

        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat = pose.pose();

        // 4 цвета (TL, TR, BL, BR) — ТЕПЕРЬ из BE/NBT
        byte[] c = be.getColors().toArray();

        // выбранная сторона
        Direction face = be.getSide();

        // чуть над гранью, чтобы не мерцало
        float eps = 0.001f;

        float U0 = sprite.getU0(), U1 = sprite.getU1();
        float V0 = sprite.getV0(), V1 = sprite.getV1();

        int overlay = OverlayTexture.NO_OVERLAY;

        // свет: либо как пришло (правильно), либо fullbright (для отладки)
        int light = DBG_FORCE_FULLBRIGHT ? LightTexture.FULL_BRIGHT : packedLight;

        // лог раз в ~2 секунды (40 тиков)
        if (DBG_LOG_EVERY_2S && Minecraft.getInstance().player != null) {
            int t = Minecraft.getInstance().player.tickCount;
            if ((t % 40) == 0) {
                System.out.println("[QuickLink][BER] pos=" + be.getBlockPos()
                        + " side=" + face
                        + " colors=" + java.util.Arrays.toString(c)
                        + " packedLight=0x" + Integer.toHexString(packedLight)
                        + " usingLight=0x" + Integer.toHexString(light));
            }
        }

        // Рисуем либо на всех гранях (редко нужно), либо только на выбранной
        if (DBG_DRAW_ALL_FACES) {
            for (Direction d : Direction.values()) {
                draw4Quadrants(vc, pose, mat, d, eps, U0, U1, V0, V1, c, light, overlay, false);
            }
        } else {
            draw4Quadrants(vc, pose, mat, face, eps, U0, U1, V0, V1, c, light, overlay, false);
        }
    }

    /** рисуем 4 квадранта на одной грани */
    private static void draw4Quadrants(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                       Direction face, float eps,
                                       float U0, float U1, float V0, float V1,
                                       byte[] c, int light, int overlay,
                                       boolean forceRed) {

        // ВАЖНО: переворачиваем вертикаль (top/bottom), чтобы “верх” реально был сверху
        // y: 0..1 (0 = верх логически), но в координатах/UV оно часто наоборот.
        // Поэтому делаем y' = 1 - y.
        // Ниже используем helper, который сам инвертит y0/y1.
        drawQuadrantFlippedY(vc, pose, mat, face, 0f, 0.5f, 0f, 0.5f, eps, U0, U1, V0, V1, c[0], light, overlay, forceRed); // TL
        drawQuadrantFlippedY(vc, pose, mat, face, 0.5f, 1f, 0f, 0.5f, eps, U0, U1, V0, V1, c[1], light, overlay, forceRed); // TR
        drawQuadrantFlippedY(vc, pose, mat, face, 0f, 0.5f, 0.5f, 1f, eps, U0, U1, V0, V1, c[2], light, overlay, forceRed); // BL
        drawQuadrantFlippedY(vc, pose, mat, face, 0.5f, 1f, 0.5f, 1f, eps, U0, U1, V0, V1, c[3], light, overlay, forceRed); // BR
    }

    private static void drawQuadrantFlippedY(
            VertexConsumer vc,
            PoseStack.Pose pose,
            Matrix4f mat,
            Direction face,
            float x0, float x1, float y0, float y1,
            float eps,
            float U0, float U1, float V0, float V1,
            byte colorId,
            int light,
            int overlay,
            boolean forceRed
    ) {
        // инверсия вертикали
        float fy0 = 1f - y1;
        float fy1 = 1f - y0;

        drawQuadrant(vc, pose, mat, face,
                x0, x1, fy0, fy1,
                eps, U0, U1, V0, V1,
                colorId, light, overlay,
                forceRed);
    }




    private static void renderFaceQuads(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                        Direction face, TextureAtlasSprite sprite, byte[] colors,
                                        int light, int overlay) {

        renderQuad(vc, pose, mat, face, 0f, 0.5f, 0.5f, 1f, sprite, colors[0], light, overlay);
        renderQuad(vc, pose, mat, face, 0.5f, 1f, 0.5f, 1f, sprite, colors[1], light, overlay);
        renderQuad(vc, pose, mat, face, 0f, 0.5f, 0f, 0.5f, sprite, colors[2], light, overlay);
        renderQuad(vc, pose, mat, face, 0.5f, 1f, 0f, 0.5f, sprite, colors[3], light, overlay);
    }

    private static void renderQuad(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat, Direction face,
                                   float u0, float u1, float v0, float v1,
                                   TextureAtlasSprite sprite, byte dyeId,
                                   int light, int overlay) {

        int rgbInt = DyeColor.byId(dyeId & 0xFF).getFireworkColor(); // яркий RGB
        float r = ((rgbInt >> 16) & 0xFF) / 255f;
        float g = ((rgbInt >> 8) & 0xFF) / 255f;
        float b = (rgbInt & 0xFF) / 255f;
        float a = 1.0f;

        float U0 = sprite.getU0(), U1 = sprite.getU1();
        float V0 = sprite.getV0(), V1 = sprite.getV1();

        float nx = face.getStepX();
        float ny = face.getStepY();
        float nz = face.getStepZ();

        switch (face) {
            case SOUTH -> quad(vc, pose, mat, nx, ny, nz,
                    u0, v0, 1f + EPS,  u1, v0, 1f + EPS,  u1, v1, 1f + EPS,  u0, v1, 1f + EPS,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);

            case NORTH -> quad(vc, pose, mat, nx, ny, nz,
                    1f - u0, v0, -EPS,  1f - u1, v0, -EPS,  1f - u1, v1, -EPS,  1f - u0, v1, -EPS,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);

            case EAST -> quad(vc, pose, mat, nx, ny, nz,
                    1f + EPS, v0, 1f - u0,  1f + EPS, v0, 1f - u1,  1f + EPS, v1, 1f - u1,  1f + EPS, v1, 1f - u0,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);

            case WEST -> quad(vc, pose, mat, nx, ny, nz,
                    -EPS, v0, u0,  -EPS, v0, u1,  -EPS, v1, u1,  -EPS, v1, u0,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);

            case UP -> quad(vc, pose, mat, nx, ny, nz,
                    u0, 1f + EPS, 1f - v0,  u1, 1f + EPS, 1f - v0,  u1, 1f + EPS, 1f - v1,  u0, 1f + EPS, 1f - v1,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);

            case DOWN -> quad(vc, pose, mat, nx, ny, nz,
                    u0, -EPS, v0,  u1, -EPS, v0,  u1, -EPS, v1,  u0, -EPS, v1,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);
        }
    }

    private static void drawQuadrant(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                     Direction face,
                                     float u0, float u1, float v0, float v1,
                                     float eps,
                                     float U0, float U1, float V0, float V1,
                                     byte dyeId,
                                     int light, int overlay,
                                     boolean forceRed) {

        float r, g, b, a = 1f;

        if (forceRed) {
            r = 1f; g = 0f; b = 0f;
        } else {
            // рекомендую яркий вариант:
            int rgb = DyeColor.byId(dyeId & 0xFF).getFireworkColor();
            r = ((rgb >> 16) & 0xFF) / 255f;
            g = ((rgb >> 8) & 0xFF) / 255f;
            b = (rgb & 0xFF) / 255f;
        }

        float nx = face.getStepX();
        float ny = face.getStepY();
        float nz = face.getStepZ();
        // Преобразуем (u,v) -> (x,y,z) для каждой грани
        switch (face) {
            case SOUTH -> quad(vc, pose, mat, nx, ny, nz,
                    u0, v0, 1f + eps,  u1, v0, 1f + eps,  u1, v1, 1f + eps,  u0, v1, 1f + eps,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);

            case NORTH -> quad(vc, pose, mat, nx, ny, nz,
                    1f - u0, v0, -eps,  1f - u1, v0, -eps,  1f - u1, v1, -eps,  1f - u0, v1, -eps,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);

            case EAST -> quad(vc, pose, mat, nx, ny, nz,
                    1f + eps, v0, 1f - u0,  1f + eps, v0, 1f - u1,  1f + eps, v1, 1f - u1,  1f + eps, v1, 1f - u0,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);

            case WEST -> quad(vc, pose, mat, nx, ny, nz,
                    -eps, v0, u0,  -eps, v0, u1,  -eps, v1, u1,  -eps, v1, u0,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);

            case UP -> quad(vc, pose, mat, nx, ny, nz,
                    u0, 1f + eps, 1f - v0,  u1, 1f + eps, 1f - v0,  u1, 1f + eps, 1f - v1,  u0, 1f + eps, 1f - v1,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);

            case DOWN -> quad(vc, pose, mat, nx, ny, nz,
                    u0, -eps, v0,  u1, -eps, v0,  u1, -eps, v1,  u0, -eps, v1,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);
        }
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                             float nx, float ny, float nz,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float x4, float y4, float z4,
                             float U0, float V0, float U1, float V1,
                             float r, float g, float b, float a,
                             int light, int overlay) {

        v(vc, pose, mat, x1, y1, z1, U0, V0, r, g, b, a, nx, ny, nz, light, overlay);
        v(vc, pose, mat, x2, y2, z2, U1, V0, r, g, b, a, nx, ny, nz, light, overlay);
        v(vc, pose, mat, x3, y3, z3, U1, V1, r, g, b, a, nx, ny, nz, light, overlay);
        v(vc, pose, mat, x4, y4, z4, U0, V1, r, g, b, a, nx, ny, nz, light, overlay);
    }

    private static void v(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                          float x, float y, float z,
                          float u, float v,
                          float r, float g, float b, float a,
                          float nx, float ny, float nz,
                          int light, int overlay) {
        vc.addVertex(mat, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }


    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                               float x, float y, float z,
                               float u, float v,
                               float r, float g, float b, float a,
                               float nx, float ny, float nz,
                               int light, int overlay) {

        vc.addVertex(mat, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }



    // SOUTH/NORTH: (x,y,zconst)
    private static void drawFace(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat, Direction face,
                                 float x0, float x1, float y0, float y1, float z,
                                 float U0, float U1, float V0, float V1,
                                 float r, float g, float b, float a,
                                 int light, int overlay) {

        float nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();

        v(vc, pose, mat, x0, y1, z, U0, V0, r,g,b,a, nx,ny,nz, light, overlay);
        v(vc, pose, mat, x1, y1, z, U1, V0, r,g,b,a, nx,ny,nz, light, overlay);
        v(vc, pose, mat, x1, y0, z, U1, V1, r,g,b,a, nx,ny,nz, light, overlay);
        v(vc, pose, mat, x0, y0, z, U0, V1, r,g,b,a, nx,ny,nz, light, overlay);
    }

    // EAST/WEST: (xconst, y, z)
    private static void drawFaceX(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat, Direction face,
                                  float x,
                                  float y0, float y1,
                                  float z0, float z1,
                                  float U0, float U1, float V0, float V1,
                                  float r, float g, float b, float a,
                                  int light, int overlay) {
        float nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();

        v(vc, pose, mat, x, y1, z0, U0, V0, r,g,b,a, nx,ny,nz, light, overlay);
        v(vc, pose, mat, x, y1, z1, U1, V0, r,g,b,a, nx,ny,nz, light, overlay);
        v(vc, pose, mat, x, y0, z1, U1, V1, r,g,b,a, nx,ny,nz, light, overlay);
        v(vc, pose, mat, x, y0, z0, U0, V1, r,g,b,a, nx,ny,nz, light, overlay);
    }

    // UP: (x, yconst, z)
    private static void drawFaceYUp(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat, Direction face,
                                    float x0, float x1,
                                    float y,
                                    float z0, float z1,
                                    float U0, float U1, float V0, float V1,
                                    float r, float g, float b, float a,
                                    int light, int overlay) {
        float nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();

        v(vc, pose, mat, x0, y, z0, U0, V0, r,g,b,a, nx,ny,nz, light, overlay);
        v(vc, pose, mat, x1, y, z0, U1, V0, r,g,b,a, nx,ny,nz, light, overlay);
        v(vc, pose, mat, x1, y, z1, U1, V1, r,g,b,a, nx,ny,nz, light, overlay);
        v(vc, pose, mat, x0, y, z1, U0, V1, r,g,b,a, nx,ny,nz, light, overlay);
    }

    // DOWN: (x, yconst, z)
    private static void drawFaceYDown(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat, Direction face,
                                      float x0, float x1,
                                      float y,
                                      float z0, float z1,
                                      float U0, float U1, float V0, float V1,
                                      float r, float g, float b, float a,
                                      int light, int overlay) {
        float nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();

        v(vc, pose, mat, x0, y, z0, U0, V0, r,g,b,a, nx,ny,nz, light, overlay);
        v(vc, pose, mat, x1, y, z0, U1, V0, r,g,b,a, nx,ny,nz, light, overlay);
        v(vc, pose, mat, x1, y, z1, U1, V1, r,g,b,a, nx,ny,nz, light, overlay);
        v(vc, pose, mat, x0, y, z1, U0, V1, r,g,b,a, nx,ny,nz, light, overlay);
    }



}
