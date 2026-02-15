package com.maximpolyakov.quicklink.fabric.client;

import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.fabric.blockentity.FluidPlugBlockEntity;
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

public class FluidPlugBlockEntityRenderer implements BlockEntityRenderer<FluidPlugBlockEntity> {

    private static final float EPS = 0.001f;
    private static final float FACE_MIN = 2f / 16f;
    private static final float FACE_MAX = 14f / 16f;

    private static final ResourceLocation WHITE_TEX =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block/white_wool");

    // ===== “Variant C” knobs =====
    private static final int LIGHT_BOOST_BLOCK = 5; // 4..8
    private static final int LIGHT_BOOST_SKY   = 2; // 1..5
    private static final float COLOR_GAMMA = 0.80f; // 0.75..0.90
    // ============================

    // ===== Role frame knobs =====
    private static final float FRAME_THICK = 0.035f;
    private static final float FRAME_ALPHA = 0.95f;

    // PLUG = orange
    private static final float PLUG_R = 1.0f;
    private static final float PLUG_G = 0.55f;
    private static final float PLUG_B = 0.10f;

    // POINT = green
    private static final float POINT_R = 0.15f;
    private static final float POINT_G = 1.00f;
    private static final float POINT_B = 0.25f;
    // ============================

    // ===== Disabled X knobs (tuned) =====
    private static final float X_THICK     = 0.055f; // чуть толще
    private static final float X_ALPHA     = 0.95f;
    private static final int   X_SEGMENTS  = 11;     // больше сегментов => меньше “квадратиков”
    private static final float X_GAP_RATIO = 0.45f;  // больше “пустоты” => реально пунктир
    // =============================

    public FluidPlugBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(FluidPlugBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {

        // ===== DIAGNOSTIC SWITCH =====
        final boolean DBG_FORCE_FULLBRIGHT = false;
        // =============================

        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS));

        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(WHITE_TEX);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat = pose.pose();

        float U0 = sprite.getU0(), U1 = sprite.getU1();
        float V0 = sprite.getV0(), V1 = sprite.getV1();

        int overlay = OverlayTexture.NO_OVERLAY;

        int light = DBG_FORCE_FULLBRIGHT
                ? LightTexture.FULL_BRIGHT
                : boostLight(packedLight, LIGHT_BOOST_BLOCK, LIGHT_BOOST_SKY);

        // 1) Color quadrants (only where != UNSET)
        for (Direction face : Direction.values()) {
            byte[] c = be.getColors(face).toArray();
            if (!be.getColors(face).isAllUnset()) {
                draw4QuadrantsSelective(vc, pose, mat, face, EPS, U0, U1, V0, V1, c, light, overlay);
            }
        }

        // 2) Role frames + disabled X
        for (Direction face : Direction.values()) {
            FluidPlugBlockEntity.SideRole role = be.getRole(face);
            if (role == FluidPlugBlockEntity.SideRole.NONE) continue;

            boolean on = be.isSideEnabled(face);

            if (on) {
                switch (role) {
                    case PLUG -> drawFrame(vc, pose, mat, face, EPS, U0, U1, V0, V1,
                            PLUG_R, PLUG_G, PLUG_B, FRAME_ALPHA,
                            FRAME_THICK, light, overlay);
                    case POINT -> drawFrame(vc, pose, mat, face, EPS, U0, U1, V0, V1,
                            POINT_R, POINT_G, POINT_B, FRAME_ALPHA,
                            FRAME_THICK, light, overlay);
                    case BOTH -> {
                        drawFrame(vc, pose, mat, face, EPS, U0, U1, V0, V1,
                                PLUG_R, PLUG_G, PLUG_B, FRAME_ALPHA,
                                FRAME_THICK, light, overlay);
                        drawFrame(vc, pose, mat, face, EPS, U0, U1, V0, V1,
                                POINT_R, POINT_G, POINT_B, FRAME_ALPHA,
                                FRAME_THICK * 0.55f, light, overlay);
                    }
                    case NONE -> { }
                }
            } else {
                // disabled: red dashed X
                drawCrossXDashedAxisAligned(vc, pose, mat, face, EPS, U0, U1, V0, V1,
                        1.0f, 0.1f, 0.1f, X_ALPHA,
                        X_THICK, light, overlay);
            }
        }
    }

    // ---------------- Quadrants ----------------

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

    private static void drawQuadrantFlippedY(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                             Direction face,
                                             float x0, float x1, float y0, float y1,
                                             float eps,
                                             float U0, float U1, float V0, float V1,
                                             byte dyeId,
                                             int light, int overlay) {
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

        r = gammaLift(r, COLOR_GAMMA);
        g = gammaLift(g, COLOR_GAMMA);
        b = gammaLift(b, COLOR_GAMMA);

        drawRectOnFace(vc, pose, mat, face, eps,
                u0, u1, v0, v1,
                U0, U1, V0, V1,
                r, g, b, 1f,
                light, overlay);
    }

    // ---------------- Role frame ----------------

    private static void drawFrame(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                  Direction face, float eps,
                                  float U0, float U1, float V0, float V1,
                                  float r, float g, float b, float a,
                                  float thick,
                                  int light, int overlay) {

        // top
        drawRectOnFace(vc, pose, mat, face, eps,
                0f, 1f, 0f, thick,
                U0, U1, V0, V1, r, g, b, a, light, overlay);

        // bottom
        drawRectOnFace(vc, pose, mat, face, eps,
                0f, 1f, 1f - thick, 1f,
                U0, U1, V0, V1, r, g, b, a, light, overlay);

        // left
        drawRectOnFace(vc, pose, mat, face, eps,
                0f, thick, 0f, 1f,
                U0, U1, V0, V1, r, g, b, a, light, overlay);

        // right
        drawRectOnFace(vc, pose, mat, face, eps,
                1f - thick, 1f, 0f, 1f,
                U0, U1, V0, V1, r, g, b, a, light, overlay);
    }

    // ---------------- Disabled: dashed X (axis-aligned rectangles) ----------------

    private static void drawCrossXDashedAxisAligned(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                                    Direction face, float eps,
                                                    float U0, float U1, float V0, float V1,
                                                    float r, float g, float b, float a,
                                                    float thick,
                                                    int light, int overlay) {

        // diag 1: (0,0)->(1,1)
        drawDashedDiagAxisAligned(vc, pose, mat, face, eps, U0, U1, V0, V1,
                r, g, b, a, thick, light, overlay,
                0f, 0f, 1f, 1f);

        // diag 2: (1,0)->(0,1)
        drawDashedDiagAxisAligned(vc, pose, mat, face, eps, U0, U1, V0, V1,
                r, g, b, a, thick, light, overlay,
                1f, 0f, 0f, 1f);
    }

    private static void drawDashedDiagAxisAligned(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                                  Direction face, float eps,
                                                  float U0, float U1, float V0, float V1,
                                                  float r, float g, float b, float a,
                                                  float thick,
                                                  int light, int overlay,
                                                  float uA, float vA, float uB, float vB) {

        int segs = Math.max(2, X_SEGMENTS);
        float gapRatio = clamp01(X_GAP_RATIO);

        for (int i = 0; i < segs; i++) {
            float t0 = (float) i / segs;
            float t1 = (float) (i + 1) / segs;

            // gap inside each segment
            float len = t1 - t0;
            float cut = len * gapRatio * 0.5f;
            t0 += cut;
            t1 -= cut;
            if (t1 <= t0) continue;

            float su0 = lerp(uA, uB, t0);
            float sv0 = lerp(vA, vB, t0);
            float su1 = lerp(uA, uB, t1);
            float sv1 = lerp(vA, vB, t1);

            // axis-aligned rectangle around the segment bbox + padding
            float pad = thick * 0.5f;

            float uu0 = Math.min(su0, su1) - pad;
            float uu1 = Math.max(su0, su1) + pad;
            float vv0 = Math.min(sv0, sv1) - pad;
            float vv1 = Math.max(sv0, sv1) + pad;

            uu0 = clamp01(uu0);
            uu1 = clamp01(uu1);
            vv0 = clamp01(vv0);
            vv1 = clamp01(vv1);

            drawRectOnFace(vc, pose, mat, face, eps,
                    uu0, uu1, vv0, vv1,
                    U0, U1, V0, V1,
                    r, g, b, a,
                    light, overlay);
        }
    }

    // ---------------- Core: draw rect on a face (FIXED UVs) ----------------

    private static void drawRectOnFace(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                       Direction face, float eps,
                                       float u0, float u1, float v0, float v1,
                                       float U0, float U1, float V0, float V1,
                                       float r, float g, float b, float a,
                                       int light, int overlay) {

        float uu0 = Math.min(u0, u1);
        float uu1 = Math.max(u0, u1);
        float vv0 = Math.min(v0, v1);
        float vv1 = Math.max(v0, v1);

        float su0 = toFaceCoord(uu0);
        float su1 = toFaceCoord(uu1);
        float sv0 = toFaceCoord(vv0);
        float sv1 = toFaceCoord(vv1);

        // UVs proportional to local rect area (prevents “full texture per strip” stretching)
        float tu0 = lerp(U0, U1, uu0);
        float tu1 = lerp(U0, U1, uu1);
        float tv0 = lerp(V0, V1, vv0);
        float tv1 = lerp(V0, V1, vv1);

        float nx = face.getStepX();
        float ny = face.getStepY();
        float nz = face.getStepZ();

        switch (face) {
            case SOUTH -> quad(vc, pose, mat, nx, ny, nz,
                    su0, sv0, FACE_MAX + eps,  su1, sv0, FACE_MAX + eps,  su1, sv1, FACE_MAX + eps,  su0, sv1, FACE_MAX + eps,
                    tu0, tv0, tu1, tv1, r, g, b, a, light, overlay);

            case NORTH -> quad(vc, pose, mat, nx, ny, nz,
                    1f - su0, sv0, FACE_MIN - eps,  1f - su1, sv0, FACE_MIN - eps,  1f - su1, sv1, FACE_MIN - eps,  1f - su0, sv1, FACE_MIN - eps,
                    tu0, tv0, tu1, tv1, r, g, b, a, light, overlay);

            case EAST -> quad(vc, pose, mat, nx, ny, nz,
                    FACE_MAX + eps, sv0, 1f - su0,  FACE_MAX + eps, sv0, 1f - su1,  FACE_MAX + eps, sv1, 1f - su1,  FACE_MAX + eps, sv1, 1f - su0,
                    tu0, tv0, tu1, tv1, r, g, b, a, light, overlay);

            case WEST -> quad(vc, pose, mat, nx, ny, nz,
                    FACE_MIN - eps, sv0, su0,  FACE_MIN - eps, sv0, su1,  FACE_MIN - eps, sv1, su1,  FACE_MIN - eps, sv1, su0,
                    tu0, tv0, tu1, tv1, r, g, b, a, light, overlay);

            case UP -> quad(vc, pose, mat, nx, ny, nz,
                    su0, FACE_MAX + eps, 1f - sv0,  su1, FACE_MAX + eps, 1f - sv0,  su1, FACE_MAX + eps, 1f - sv1,  su0, FACE_MAX + eps, 1f - sv1,
                    tu0, tv0, tu1, tv1, r, g, b, a, light, overlay);

            case DOWN -> quad(vc, pose, mat, nx, ny, nz,
                    su0, FACE_MIN - eps, sv0,  su1, FACE_MIN - eps, sv0,  su1, FACE_MIN - eps, sv1,  su0, FACE_MIN - eps, sv1,
                    tu0, tv0, tu1, tv1, r, g, b, a, light, overlay);
        }
    }

    // ---------------- Low-level quad ----------------

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

    // ---------------- Utils ----------------

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

    private static float toFaceCoord(float t) {
        return FACE_MIN + (FACE_MAX - FACE_MIN) * clamp01(t);
    }

    private static float clamp01(float x) {
        if (x < 0f) return 0f;
        if (x > 1f) return 1f;
        return x;
    }
}
