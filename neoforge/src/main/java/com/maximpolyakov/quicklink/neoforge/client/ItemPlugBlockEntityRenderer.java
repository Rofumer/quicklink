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

    // Белая текстура из атласа, которую мы тонируем цветом (и для квадрантов, и для рамки/креста)
    private static final ResourceLocation WHITE_TEX =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block/white_wool");

    // ===== “Variant C” knobs =====
    private static final int LIGHT_BOOST_BLOCK = 5; // 4..8
    private static final int LIGHT_BOOST_SKY   = 2; // 1..5
    private static final float COLOR_GAMMA = 0.80f; // 0.75..0.90
    // ============================

    // ===== Role marker knobs =====
    private static final float FRAME_THICK = 0.06f;   // толщина рамки в UV (0..1)
    private static final float X_THICK     = 0.05f;   // толщина штриха креста
    private static final float MARKER_ALPHA = 0.95f;  // прозрачность маркеров
    // ============================

    public ItemPlugBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(ItemPlugBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {

        final boolean DBG_FORCE_FULLBRIGHT = false;

        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS));
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(WHITE_TEX);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat = pose.pose();

        // Цвет сети (квадранты)
        byte[] c = be.getColors().toArray();
        boolean allUnset = be.getColors().isAllUnset();

        float U0 = sprite.getU0(), U1 = sprite.getU1();
        float V0 = sprite.getV0(), V1 = sprite.getV1();

        int overlay = OverlayTexture.NO_OVERLAY;

        int light;
        if (DBG_FORCE_FULLBRIGHT) {
            light = LightTexture.FULL_BRIGHT;
        } else {
            light = boostLight(packedLight, LIGHT_BOOST_BLOCK, LIGHT_BOOST_SKY);
        }

        // Рисуем на всех гранях, где роль != NONE:
        for (Direction face : Direction.values()) {
            ItemPlugBlockEntity.SideRole role = be.getRole(face);
            if (role == ItemPlugBlockEntity.SideRole.NONE) continue;

            boolean sideOn = be.isSideEnabled(face);

            // 1) Квадранты сети (только если реально что-то покрашено)
            if (!allUnset) {
                draw4QuadrantsSelective(vc, pose, mat, face, EPS, U0, U1, V0, V1, c, light, overlay);
            }

            // 2) Рамка роли (PLUG/POINT)
            if (role == ItemPlugBlockEntity.SideRole.PLUG) {
                // оранжевая
                drawFrame(vc, pose, mat, face, EPS,
                        U0, U1, V0, V1,
                        1.0f, 0.55f, 0.05f, MARKER_ALPHA,
                        FRAME_THICK, light, overlay);
            } else if (role == ItemPlugBlockEntity.SideRole.POINT) {
                // зелёная
                drawFrame(vc, pose, mat, face, EPS,
                        U0, U1, V0, V1,
                        0.10f, 1.0f, 0.20f, MARKER_ALPHA,
                        FRAME_THICK, light, overlay);
            }

            // 3) Disabled: крест “X” поверх (если OFF)
            if (!sideOn) {
                // тёмно-серый крест, чтобы читалось поверх цвета
                drawCrossX(vc, pose, mat, face, EPS,
                        U0, U1, V0, V1,
                        0.10f, 0.10f, 0.10f, 0.90f,
                        X_THICK, light, overlay);
            }
        }
    }

    /**
     * Рисуем 4 квадранта на одной грани, НО только те, что не UNSET.
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

        // “Variant C”: лёгкое высветление цвета
        r = gammaLift(r, COLOR_GAMMA);
        g = gammaLift(g, COLOR_GAMMA);
        b = gammaLift(b, COLOR_GAMMA);

        drawSolidRect(vc, pose, mat, face,
                u0, u1, v0, v1,
                eps, U0, U1, V0, V1,
                r, g, b, 1f,
                light, overlay);
    }

    /**
     * Рамка по краям UV [0..1] с толщиной t.
     */
    private static void drawFrame(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                  Direction face, float eps,
                                  float U0, float U1, float V0, float V1,
                                  float r, float g, float b, float a,
                                  float t,
                                  int light, int overlay) {

        float tClamped = clamp01(t);

        // top
        drawSolidRect(vc, pose, mat, face, 0f, 1f, 0f, tClamped, eps, U0, U1, V0, V1, r,g,b,a, light, overlay);
        // bottom
        drawSolidRect(vc, pose, mat, face, 0f, 1f, 1f - tClamped, 1f, eps, U0, U1, V0, V1, r,g,b,a, light, overlay);
        // left
        drawSolidRect(vc, pose, mat, face, 0f, tClamped, 0f, 1f, eps, U0, U1, V0, V1, r,g,b,a, light, overlay);
        // right
        drawSolidRect(vc, pose, mat, face, 1f - tClamped, 1f, 0f, 1f, eps, U0, U1, V0, V1, r,g,b,a, light, overlay);
    }

    /**
     * Крест X как две диагональные “полоски” в UV-плоскости.
     */
    private static void drawCrossX(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                   Direction face, float eps,
                                   float U0, float U1, float V0, float V1,
                                   float r, float g, float b, float a,
                                   float thickness,
                                   int light, int overlay) {

        float t = Math.max(0.01f, Math.min(0.25f, thickness));

        // Диагональ 1: (0,0) -> (1,1)
        drawDiagStrip(vc, pose, mat, face, eps, U0, U1, V0, V1, r,g,b,a, light, overlay,
                0f, 0f, 1f, 1f, t);

        // Диагональ 2: (1,0) -> (0,1)
        drawDiagStrip(vc, pose, mat, face, eps, U0, U1, V0, V1, r,g,b,a, light, overlay,
                1f, 0f, 0f, 1f, t);
    }

    /**
     * Диагональная полоска (u0,v0)->(u1,v1) толщины t в UV.
     */
    private static void drawDiagStrip(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                      Direction face, float eps,
                                      float U0, float U1, float V0, float V1,
                                      float r, float g, float b, float a,
                                      int light, int overlay,
                                      float uA, float vA, float uB, float vB,
                                      float t) {

        float dx = uB - uA;
        float dy = vB - vA;
        float len = (float) Math.sqrt(dx*dx + dy*dy);
        if (len < 1e-6f) return;

        // Перпендикуляр в UV
        float px = -dy / len;
        float py =  dx / len;

        float hu = px * (t * 0.5f);
        float hv = py * (t * 0.5f);

        // 4 угла полоски
        float u1 = uA + hu, v1 = vA + hv;
        float u2 = uB + hu, v2 = vB + hv;
        float u3 = uB - hu, v3 = vB - hv;
        float u4 = uA - hu, v4 = vA - hv;

        // Текстурные UV (линейно)
        float TU1 = lerp(U0, U1, u1), TV1 = lerp(V0, V1, v1);
        float TU2 = lerp(U0, U1, u2), TV2 = lerp(V0, V1, v2);
        float TU3 = lerp(U0, U1, u3), TV3 = lerp(V0, V1, v3);
        float TU4 = lerp(U0, U1, u4), TV4 = lerp(V0, V1, v4);

        float nx = face.getStepX();
        float ny = face.getStepY();
        float nz = face.getStepZ();

        // Конвертим UV->XYZ для этой грани и кидаем 4 вершины
        float[] p1 = uvToFaceXYZ(face, u1, v1, eps);
        float[] p2 = uvToFaceXYZ(face, u2, v2, eps);
        float[] p3 = uvToFaceXYZ(face, u3, v3, eps);
        float[] p4 = uvToFaceXYZ(face, u4, v4, eps);

        v(vc, pose, mat, p1[0], p1[1], p1[2], TU1, TV1, r,g,b,a, nx,ny,nz, light, overlay);
        v(vc, pose, mat, p2[0], p2[1], p2[2], TU2, TV2, r,g,b,a, nx,ny,nz, light, overlay);
        v(vc, pose, mat, p3[0], p3[1], p3[2], TU3, TV3, r,g,b,a, nx,ny,nz, light, overlay);
        v(vc, pose, mat, p4[0], p4[1], p4[2], TU4, TV4, r,g,b,a, nx,ny,nz, light, overlay);
    }

    /**
     * Прямоугольник в UV-координатах (оси-aligned) на выбранной грани.
     * u,v в диапазоне [0..1].
     */
    private static void drawSolidRect(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                      Direction face,
                                      float u0, float u1, float v0, float v1,
                                      float eps,
                                      float U0, float U1, float V0, float V1,
                                      float r, float g, float b, float a,
                                      int light, int overlay) {

        float nx = face.getStepX();
        float ny = face.getStepY();
        float nz = face.getStepZ();

        switch (face) {
            case SOUTH -> quad(vc, pose, mat, nx, ny, nz,
                    u0, v0, 1f + eps,  u1, v0, 1f + eps,  u1, v1, 1f + eps,  u0, v1, 1f + eps,
                    lerp(U0, U1, u0), lerp(V0, V1, v0),
                    lerp(U0, U1, u1), lerp(V0, V1, v1),
                    r, g, b, a, light, overlay);

            case NORTH -> quad(vc, pose, mat, nx, ny, nz,
                    1f - u0, v0, -eps,  1f - u1, v0, -eps,  1f - u1, v1, -eps,  1f - u0, v1, -eps,
                    lerp(U0, U1, u0), lerp(V0, V1, v0),
                    lerp(U0, U1, u1), lerp(V0, V1, v1),
                    r, g, b, a, light, overlay);

            case EAST -> quad(vc, pose, mat, nx, ny, nz,
                    1f + eps, v0, 1f - u0,  1f + eps, v0, 1f - u1,  1f + eps, v1, 1f - u1,  1f + eps, v1, 1f - u0,
                    lerp(U0, U1, u0), lerp(V0, V1, v0),
                    lerp(U0, U1, u1), lerp(V0, V1, v1),
                    r, g, b, a, light, overlay);

            case WEST -> quad(vc, pose, mat, nx, ny, nz,
                    -eps, v0, u0,  -eps, v0, u1,  -eps, v1, u1,  -eps, v1, u0,
                    lerp(U0, U1, u0), lerp(V0, V1, v0),
                    lerp(U0, U1, u1), lerp(V0, V1, v1),
                    r, g, b, a, light, overlay);

            case UP -> quad(vc, pose, mat, nx, ny, nz,
                    u0, 1f + eps, 1f - v0,  u1, 1f + eps, 1f - v0,  u1, 1f + eps, 1f - v1,  u0, 1f + eps, 1f - v1,
                    lerp(U0, U1, u0), lerp(V0, V1, v0),
                    lerp(U0, U1, u1), lerp(V0, V1, v1),
                    r, g, b, a, light, overlay);

            case DOWN -> quad(vc, pose, mat, nx, ny, nz,
                    u0, -eps, v0,  u1, -eps, v0,  u1, -eps, v1,  u0, -eps, v1,
                    lerp(U0, U1, u0), lerp(V0, V1, v0),
                    lerp(U0, U1, u1), lerp(V0, V1, v1),
                    r, g, b, a, light, overlay);
        }
    }

    private static float[] uvToFaceXYZ(Direction face, float u, float v, float eps) {
        // u,v в [0..1]
        return switch (face) {
            case SOUTH -> new float[]{u, v, 1f + eps};
            case NORTH -> new float[]{1f - u, v, -eps};
            case EAST  -> new float[]{1f + eps, v, 1f - u};
            case WEST  -> new float[]{-eps, v, u};
            case UP    -> new float[]{u, 1f + eps, 1f - v};
            case DOWN  -> new float[]{u, -eps, v};
        };
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

    private static int boostLight(int packedLight, int addBlock, int addSky) {
        if (addBlock <= 0 && addSky <= 0) return packedLight;

        int block = LightTexture.block(packedLight);
        int sky = LightTexture.sky(packedLight);

        block = Math.min(15, block + Math.max(0, addBlock));
        sky = Math.min(15, sky + Math.max(0, addSky));

        return LightTexture.pack(block, sky);
    }

    private static float gammaLift(float c, float gamma) {
        if (gamma >= 0.999f) return c;
        if (c <= 0f) return 0f;
        if (c >= 1f) return 1f;
        return (float) Math.pow(c, gamma);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
