package com.maximpolyakov.quicklink.neoforge.client;

import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.neoforge.blockentity.ItemPlugBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.DyeColor;
import org.joml.Matrix4f;

public class ItemPlugBlockEntityRenderer implements BlockEntityRenderer<ItemPlugBlockEntity> {

    private static final float EPS = 0.001f;

    // White texture from block atlas; we tint it.
    private static final ResourceLocation WHITE_TEX =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block/white_wool");

    // ===== “Variant C” knobs =====
    // Поднимаем lightmap на N пунктов (0..15). 0 = выключено.
    private static final int LIGHT_BOOST_BLOCK = 5; // попробуй 4..8
    private static final int LIGHT_BOOST_SKY   = 2; // попробуй 1..5
    // Нелинейное “высветление” цвета. 1.0 = выключено, меньше = ярче.
    private static final float COLOR_GAMMA = 0.80f; // 0.75..0.90 обычно ок
    // ============================

    public ItemPlugBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(ItemPlugBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {

        // ===== DIAGNOSTIC SWITCHES =====
        final boolean DBG_LOG_EVERY_2S = false;
        final boolean DBG_FORCE_FULLBRIGHT = false; // только для отладки
        final boolean DBG_DRAW_ALL_FACES = false;   // обычно false: рисуем только на be.getSide()
        // =================================

        // Если вообще ничего не покрашено — не рисуем оверлей (блок остаётся камнем).
        if (be.getColors().isAllUnset()) {
            return;
        }

        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS));

        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(WHITE_TEX);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat = pose.pose();

        // 4 цвета (TL, TR, BL, BR) — из BE/NBT
        byte[] c = be.getColors().toArray();

        // выбранная сторона
        Direction face = be.getSide();

        float U0 = sprite.getU0(), U1 = sprite.getU1();
        float V0 = sprite.getV0(), V1 = sprite.getV1();

        int overlay = OverlayTexture.NO_OVERLAY;

        int light;
        if (DBG_FORCE_FULLBRIGHT) {
            light = LightTexture.FULL_BRIGHT;
        } else {
            // Вариант C: берём реальный packedLight, но “чуть поднимаем”
            light = boostLight(packedLight, LIGHT_BOOST_BLOCK, LIGHT_BOOST_SKY);
        }

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

        if (DBG_DRAW_ALL_FACES) {
            for (Direction d : Direction.values()) {
                draw4QuadrantsSelective(vc, pose, mat, d, EPS, U0, U1, V0, V1, c, light, overlay);
            }
        } else {
            draw4QuadrantsSelective(vc, pose, mat, face, EPS, U0, U1, V0, V1, c, light, overlay);
        }
    }

    /**
     * Рисуем 4 квадранта на одной грани, НО только те, что не UNSET.
     * Это важно, чтобы “непокрашенные” не становились чёрными/тусклыми.
     */
    private static void draw4QuadrantsSelective(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                                Direction face, float eps,
                                                float U0, float U1, float V0, float V1,
                                                byte[] c, int light, int overlay) {

        if (c[0] != QuickLinkColors.UNSET) {
            drawQuadrantFlippedY(vc, pose, mat, face, 0f, 0.5f, 0f, 0.5f, eps, U0, U1, V0, V1, c[0], light, overlay);
        }
        if (c[1] != QuickLinkColors.UNSET) {
            drawQuadrantFlippedY(vc, pose, mat, face, 0.5f, 1f, 0f, 0.5f, eps, U0, U1, V0, V1, c[1], light, overlay);
        }
        if (c[2] != QuickLinkColors.UNSET) {
            drawQuadrantFlippedY(vc, pose, mat, face, 0f, 0.5f, 0.5f, 1f, eps, U0, U1, V0, V1, c[2], light, overlay);
        }
        if (c[3] != QuickLinkColors.UNSET) {
            drawQuadrantFlippedY(vc, pose, mat, face, 0.5f, 1f, 0.5f, 1f, eps, U0, U1, V0, V1, c[3], light, overlay);
        }
    }

    private static void drawQuadrantFlippedY(
            VertexConsumer vc,
            PoseStack.Pose pose,
            Matrix4f mat,
            Direction face,
            float x0, float x1, float y0, float y1,
            float eps,
            float U0, float U1, float V0, float V1,
            byte dyeId,
            int light,
            int overlay
    ) {
        // инверсия вертикали, чтобы “верх” действительно был сверху
        float fy0 = 1f - y1;
        float fy1 = 1f - y0;

        drawQuadrant(vc, pose, mat, face,
                x0, x1, fy0, fy1,
                eps, U0, U1, V0, V1,
                dyeId, light, overlay);
    }

    private static void drawQuadrant(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                     Direction face,
                                     float u0, float u1, float v0, float v1,
                                     float eps,
                                     float U0, float U1, float V0, float V1,
                                     byte dyeId,
                                     int light, int overlay) {

        int rgb = DyeColor.byId(dyeId & 0xFF).getFireworkColor();
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;

        // Вариант C: “чуть поднять” сам цвет (gamma-like)
        r = gammaLift(r, COLOR_GAMMA);
        g = gammaLift(g, COLOR_GAMMA);
        b = gammaLift(b, COLOR_GAMMA);

        float a = 1f;

        float nx = face.getStepX();
        float ny = face.getStepY();
        float nz = face.getStepZ();

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

    /**
     * Поднимаем упакованный lightmap:
     * block/sky — по 0..15, просто добавляем и clamp.
     */
    private static int boostLight(int packedLight, int addBlock, int addSky) {
        if (addBlock <= 0 && addSky <= 0) return packedLight;

        int block = LightTexture.block(packedLight);
        int sky = LightTexture.sky(packedLight);

        block = Math.min(15, block + Math.max(0, addBlock));
        sky = Math.min(15, sky + Math.max(0, addSky));

        return LightTexture.pack(block, sky);
    }

    /**
     * gamma < 1 => светлее. 1 => без изменений.
     */
    private static float gammaLift(float c, float gamma) {
        if (gamma >= 0.999f) return c;
        if (c <= 0f) return 0f;
        if (c >= 1f) return 1f;
        return (float) Math.pow(c, gamma);
    }
}
