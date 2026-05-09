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
    private static final float FACE_MIN = 6f / 16f;
    private static final float FACE_MAX = 10f / 16f;

    // Cubelet positions in normalized face space [0..1] (gap = 10% of face = 0.4 model units)
    private static final float NORM_A0 = 0f;
    private static final float NORM_A1 = 0.45f;
    private static final float NORM_B0 = 0.55f;
    private static final float NORM_B1 = 1f;
    private static final float QUAD_INSET = 0.10f;

    private static final ResourceLocation WHITE_TEX =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block/white_wool");

    // ===== "Variant C" knobs =====
    private static final int LIGHT_BOOST_BLOCK = 5;
    private static final int LIGHT_BOOST_SKY   = 2;
    private static final float COLOR_GAMMA = 0.80f;
    // ============================

    // ===== Leg / cone knobs =====
    private static final float STEM_HALF      = 1.0f / 16f;
    private static final float CONE_BASE_HALF = 1.5f / 16f;
    private static final float CONE_LENGTH    = 2.5f / 16f;
    private static final float LEG_ALPHA      = 1.0f;

    // PLUG = orange
    private static final float PLUG_R = 1.0f;
    private static final float PLUG_G = 0.55f;
    private static final float PLUG_B = 0.10f;

    // POINT = green
    private static final float POINT_R = 0.15f;
    private static final float POINT_G = 1.00f;
    private static final float POINT_B = 0.25f;
    // ============================

    // ===== Disabled X knobs =====
    private static final float X_THICK     = 0.05f;
    private static final float X_ALPHA     = 0.95f;
    private static final int   X_SEGMENTS  = 11;     // больше сегментов => меньше "квадратиков"
    private static final float X_GAP_RATIO = 0.45f;  // больше "пустоты" => реально пунктир
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

        // 1) Color quadrants on each cubelet face
        for (Direction face : Direction.values()) {
            byte[] c = be.getColors(face).toArray();
            if (!be.getColors(face).isAllUnset()) {
                draw4QuadrantsSelective(vc, pose, mat, face, EPS, U0, U1, V0, V1, c, light, overlay);
            }
        }

        // 2) Role frames + disabled X on each cubelet face
        for (Direction face : Direction.values()) {
            FluidPlugBlockEntity.SideRole role = be.getRole(face);
            if (role == FluidPlugBlockEntity.SideRole.NONE) continue;

            boolean on = be.isSideEnabled(face);

            if (on) {
                switch (role) {
                    case PLUG -> drawLeg(vc, pose, mat, face, true,
                            PLUG_R, PLUG_G, PLUG_B, LEG_ALPHA, 0f,
                            U0, U1, V0, V1, light, overlay);
                    case POINT -> drawLeg(vc, pose, mat, face, false,
                            POINT_R, POINT_G, POINT_B, LEG_ALPHA, 0f,
                            U0, U1, V0, V1, light, overlay);
                    case BOTH -> {
                        drawLeg(vc, pose, mat, face, true,
                                PLUG_R, PLUG_G, PLUG_B, LEG_ALPHA, -STEM_HALF,
                                U0, U1, V0, V1, light, overlay);
                        drawLeg(vc, pose, mat, face, false,
                                POINT_R, POINT_G, POINT_B, LEG_ALPHA, +STEM_HALF,
                                U0, U1, V0, V1, light, overlay);
                    }
                    case NONE -> {}
                }
            } else {
                drawCrossXDashedAxisAligned(vc, pose, mat, face, EPS, U0, U1, V0, V1,
                        1.0f, 0.1f, 0.1f, X_ALPHA,
                        X_THICK, light, overlay);
            }
        }
    }

    // ---------------- Quadrants on cubelets ----------------

    private static void draw4QuadrantsSelective(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                                Direction face, float eps,
                                                float U0, float U1, float V0, float V1,
                                                byte[] c, int light, int overlay) {
        // c[0]=top-left, c[1]=top-right, c[2]=bottom-left, c[3]=bottom-right
        float I = QUAD_INSET;
        if (c[0] != QuickLinkColors.UNSET)
            drawQuadrant(vc, pose, mat, face, NORM_A0+I, NORM_A1-I, NORM_B0+I, NORM_B1-I, eps, U0, U1, V0, V1, c[0], light, overlay);
        if (c[1] != QuickLinkColors.UNSET)
            drawQuadrant(vc, pose, mat, face, NORM_B0+I, NORM_B1-I, NORM_B0+I, NORM_B1-I, eps, U0, U1, V0, V1, c[1], light, overlay);
        if (c[2] != QuickLinkColors.UNSET)
            drawQuadrant(vc, pose, mat, face, NORM_A0+I, NORM_A1-I, NORM_A0+I, NORM_A1-I, eps, U0, U1, V0, V1, c[2], light, overlay);
        if (c[3] != QuickLinkColors.UNSET)
            drawQuadrant(vc, pose, mat, face, NORM_B0+I, NORM_B1-I, NORM_A0+I, NORM_A1-I, eps, U0, U1, V0, V1, c[3], light, overlay);
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

    // ---------------- Leg + directional cone ----------------

    private static void drawLeg(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                Direction face, boolean outward,
                                float r, float g, float b, float alpha,
                                float uOff,
                                float U0, float U1, float V0, float V1,
                                int light, int overlay) {
        float c  = 0.5f;
        float cu = c + uOff;
        float sh = STEM_HALF;
        float ch = CONE_BASE_HALF;
        float cl = CONE_LENGTH;

        int main, uAx, vAx;
        float sign;
        switch (face) {
            case SOUTH -> { main = 2; uAx = 0; vAx = 1; sign = +1f; }
            case NORTH -> { main = 2; uAx = 0; vAx = 1; sign = -1f; }
            case EAST  -> { main = 0; uAx = 2; vAx = 1; sign = +1f; }
            case WEST  -> { main = 0; uAx = 2; vAx = 1; sign = -1f; }
            case UP    -> { main = 1; uAx = 0; vAx = 2; sign = +1f; }
            case DOWN  -> { main = 1; uAx = 0; vAx = 2; sign = -1f; }
            default    -> { return; }
        }

        float inner = sign > 0 ? FACE_MAX : FACE_MIN;
        float edge  = sign > 0 ? 1.0f : 0.0f;

        float coneTip, coneBase, stemFrom, stemTo;
        if (outward) {
            coneTip  = edge;
            coneBase = edge - sign * cl;
            stemFrom = inner;
            stemTo   = coneBase;
        } else {
            coneTip  = inner;
            coneBase = inner + sign * cl;
            stemFrom = coneBase;
            stemTo   = edge;
        }

        float stemMin = Math.min(stemFrom, stemTo);
        float stemMax = Math.max(stemFrom, stemTo);

        if (stemMax > stemMin + 0.001f) {
            float[] s00 = p3(main, stemMin, uAx, cu-sh, vAx, c-sh);
            float[] s01 = p3(main, stemMin, uAx, cu-sh, vAx, c+sh);
            float[] s10 = p3(main, stemMin, uAx, cu+sh, vAx, c-sh);
            float[] s11 = p3(main, stemMin, uAx, cu+sh, vAx, c+sh);
            float[] e00 = p3(main, stemMax, uAx, cu-sh, vAx, c-sh);
            float[] e01 = p3(main, stemMax, uAx, cu-sh, vAx, c+sh);
            float[] e10 = p3(main, stemMax, uAx, cu+sh, vAx, c-sh);
            float[] e11 = p3(main, stemMax, uAx, cu+sh, vAx, c+sh);

            float[] nu = new float[3]; nu[uAx] = 1f;
            float[] nv = new float[3]; nv[vAx] = 1f;

            quad(vc,pose,mat,-nu[0],-nu[1],-nu[2], s00[0],s00[1],s00[2], s01[0],s01[1],s01[2], e01[0],e01[1],e01[2], e00[0],e00[1],e00[2], U0,V0,U1,V1, r,g,b,alpha, light,overlay);
            quad(vc,pose,mat, nu[0], nu[1], nu[2], s10[0],s10[1],s10[2], e10[0],e10[1],e10[2], e11[0],e11[1],e11[2], s11[0],s11[1],s11[2], U0,V0,U1,V1, r,g,b,alpha, light,overlay);
            quad(vc,pose,mat,-nv[0],-nv[1],-nv[2], s00[0],s00[1],s00[2], e00[0],e00[1],e00[2], e10[0],e10[1],e10[2], s10[0],s10[1],s10[2], U0,V0,U1,V1, r,g,b,alpha, light,overlay);
            quad(vc,pose,mat, nv[0], nv[1], nv[2], s01[0],s01[1],s01[2], s11[0],s11[1],s11[2], e11[0],e11[1],e11[2], e01[0],e01[1],e01[2], U0,V0,U1,V1, r,g,b,alpha, light,overlay);
        }

        float[] tip = p3(main, coneTip,  uAx, cu,    vAx, c   );
        float[] BL  = p3(main, coneBase, uAx, cu-ch, vAx, c-ch);
        float[] BR  = p3(main, coneBase, uAx, cu+ch, vAx, c-ch);
        float[] TL  = p3(main, coneBase, uAx, cu-ch, vAx, c+ch);
        float[] TR  = p3(main, coneBase, uAx, cu+ch, vAx, c+ch);

        float[] nu = new float[3]; nu[uAx] = 1f;
        float[] nv = new float[3]; nv[vAx] = 1f;

        quad(vc,pose,mat,-nv[0],-nv[1],-nv[2], tip[0],tip[1],tip[2], tip[0],tip[1],tip[2], BL[0],BL[1],BL[2], BR[0],BR[1],BR[2], U0,V0,U1,V1, r,g,b,alpha, light,overlay);
        quad(vc,pose,mat, nv[0], nv[1], nv[2], tip[0],tip[1],tip[2], tip[0],tip[1],tip[2], TR[0],TR[1],TR[2], TL[0],TL[1],TL[2], U0,V0,U1,V1, r,g,b,alpha, light,overlay);
        quad(vc,pose,mat,-nu[0],-nu[1],-nu[2], tip[0],tip[1],tip[2], tip[0],tip[1],tip[2], TL[0],TL[1],TL[2], BL[0],BL[1],BL[2], U0,V0,U1,V1, r,g,b,alpha, light,overlay);
        quad(vc,pose,mat, nu[0], nu[1], nu[2], tip[0],tip[1],tip[2], tip[0],tip[1],tip[2], BR[0],BR[1],BR[2], TR[0],TR[1],TR[2], U0,V0,U1,V1, r,g,b,alpha, light,overlay);
    }

    private static float[] p3(int ax0, float v0, int ax1, float v1, int ax2, float v2) {
        float[] p = new float[3];
        p[ax0] = v0; p[ax1] = v1; p[ax2] = v2;
        return p;
    }

    // ---------------- Disabled: dashed X on each cubelet ----------------

    private static void drawCrossXDashedAxisAligned(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                                    Direction face, float eps,
                                                    float U0, float U1, float V0, float V1,
                                                    float r, float g, float b, float a,
                                                    float thick,
                                                    int light, int overlay) {
        drawCrossOnCell(vc, pose, mat, face, eps, U0, U1, V0, V1, NORM_A0, NORM_A1, NORM_A0, NORM_A1, r, g, b, a, thick, light, overlay);
        drawCrossOnCell(vc, pose, mat, face, eps, U0, U1, V0, V1, NORM_B0, NORM_B1, NORM_A0, NORM_A1, r, g, b, a, thick, light, overlay);
        drawCrossOnCell(vc, pose, mat, face, eps, U0, U1, V0, V1, NORM_A0, NORM_A1, NORM_B0, NORM_B1, r, g, b, a, thick, light, overlay);
        drawCrossOnCell(vc, pose, mat, face, eps, U0, U1, V0, V1, NORM_B0, NORM_B1, NORM_B0, NORM_B1, r, g, b, a, thick, light, overlay);
    }

    private static void drawCrossOnCell(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                        Direction face, float eps,
                                        float U0, float U1, float V0, float V1,
                                        float cu0, float cu1, float cv0, float cv1,
                                        float r, float g, float b, float a, float thick,
                                        int light, int overlay) {
        drawDashedDiagAxisAligned(vc, pose, mat, face, eps, U0, U1, V0, V1,
                r, g, b, a, thick, light, overlay, cu0, cv0, cu1, cv1);
        drawDashedDiagAxisAligned(vc, pose, mat, face, eps, U0, U1, V0, V1,
                r, g, b, a, thick, light, overlay, cu1, cv0, cu0, cv1);
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

            float len = t1 - t0;
            float cut = len * gapRatio * 0.5f;
            t0 += cut;
            t1 -= cut;
            if (t1 <= t0) continue;

            float su0 = lerp(uA, uB, t0);
            float sv0 = lerp(vA, vB, t0);
            float su1 = lerp(uA, uB, t1);
            float sv1 = lerp(vA, vB, t1);

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

    // ---------------- Core: draw rect on a face ----------------

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

        float nx = face.getStepX();
        float ny = face.getStepY();
        float nz = face.getStepZ();

        switch (face) {
            case SOUTH -> quad(vc, pose, mat, nx, ny, nz,
                    su0, sv0, FACE_MAX + eps,  su1, sv0, FACE_MAX + eps,  su1, sv1, FACE_MAX + eps,  su0, sv1, FACE_MAX + eps,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);

            case NORTH -> quad(vc, pose, mat, nx, ny, nz,
                    1f - su0, sv0, FACE_MIN - eps,  1f - su1, sv0, FACE_MIN - eps,  1f - su1, sv1, FACE_MIN - eps,  1f - su0, sv1, FACE_MIN - eps,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);

            case EAST -> quad(vc, pose, mat, nx, ny, nz,
                    FACE_MAX + eps, sv0, 1f - su0,  FACE_MAX + eps, sv0, 1f - su1,  FACE_MAX + eps, sv1, 1f - su1,  FACE_MAX + eps, sv1, 1f - su0,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);

            case WEST -> quad(vc, pose, mat, nx, ny, nz,
                    FACE_MIN - eps, sv0, su0,  FACE_MIN - eps, sv0, su1,  FACE_MIN - eps, sv1, su1,  FACE_MIN - eps, sv1, su0,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);

            case UP -> quad(vc, pose, mat, nx, ny, nz,
                    su0, FACE_MAX + eps, 1f - sv0,  su1, FACE_MAX + eps, 1f - sv0,  su1, FACE_MAX + eps, 1f - sv1,  su0, FACE_MAX + eps, 1f - sv1,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);

            case DOWN -> quad(vc, pose, mat, nx, ny, nz,
                    su0, FACE_MIN - eps, sv0,  su1, FACE_MIN - eps, sv0,  su1, FACE_MIN - eps, sv1,  su0, FACE_MIN - eps, sv1,
                    U0, V0, U1, V1, r, g, b, a, light, overlay);
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
