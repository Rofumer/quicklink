package com.maximpolyakov.quicklink.forge.client;

import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.forge.blockentity.ItemPlugBlockEntity;
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
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public class ItemPlugBlockEntityRenderer implements BlockEntityRenderer<ItemPlugBlockEntity> {

    private static final float EPS       = 0.001f;
    private static final float FACE_MIN  = 6f / 16f;
    private static final float FACE_MAX  = 10f / 16f;

    private static final float NORM_A0 = 0f;
    private static final float NORM_A1 = 0.45f;
    private static final float NORM_B0 = 0.55f;
    private static final float NORM_B1 = 1f;
    private static final float QUAD_INSET = 0.10f;

    private static final ResourceLocation WHITE_TEX =
            new ResourceLocation("minecraft", "block/white_wool");

    private static final int   LIGHT_BOOST_BLOCK = 5;
    private static final int   LIGHT_BOOST_SKY   = 2;
    private static final float COLOR_GAMMA        = 0.80f;

    private static final float STEM_HALF      = 0.5f / 16f;
    private static final float CONE_BASE_HALF = 0.8f / 16f;
    private static final float CONE_LENGTH    = 2.0f / 16f;
    private static final float LEG_ALPHA      = 1.0f;

    private static final float PLUG_R  = 1.0f,  PLUG_G  = 0.55f, PLUG_B  = 0.10f;
    private static final float POINT_R = 0.15f, POINT_G = 1.00f, POINT_B = 0.25f;

    private static final float X_THICK     = 0.05f;
    private static final float X_ALPHA     = 0.95f;
    private static final int   X_SEGMENTS  = 7;
    private static final float X_GAP_RATIO = 0.35f;

    public ItemPlugBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(ItemPlugBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {

        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS));
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(WHITE_TEX);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat  = pose.pose();
        Matrix3f norm = pose.normal();

        float U0 = sprite.getU0(), U1 = sprite.getU1();
        float V0 = sprite.getV0(), V1 = sprite.getV1();

        int overlay = OverlayTexture.NO_OVERLAY;
        int light   = boostLight(packedLight, LIGHT_BOOST_BLOCK, LIGHT_BOOST_SKY);

        for (Direction face : Direction.values()) {
            byte[] c = be.getColors(face).toArray();
            if (!be.getColors(face).isAllUnset())
                draw4QuadrantsSelective(vc, mat, norm, face, EPS, U0, U1, V0, V1, c, light, overlay);
        }

        for (Direction face : Direction.values()) {
            ItemPlugBlockEntity.SideRole role = be.getRole(face);
            if (role == ItemPlugBlockEntity.SideRole.NONE) continue;

            boolean on = be.isSideEnabled(face);
            if (on) {
                switch (role) {
                    case PLUG ->  drawLeg(vc, mat, norm, face, true,  PLUG_R,  PLUG_G,  PLUG_B,  LEG_ALPHA, 0f,           U0, U1, V0, V1, light, overlay);
                    case POINT -> drawLeg(vc, mat, norm, face, false, POINT_R, POINT_G, POINT_B, LEG_ALPHA, 0f,           U0, U1, V0, V1, light, overlay);
                    case BOTH ->  { drawLeg(vc, mat, norm, face, true,  PLUG_R,  PLUG_G,  PLUG_B,  LEG_ALPHA, -STEM_HALF, U0, U1, V0, V1, light, overlay);
                                    drawLeg(vc, mat, norm, face, false, POINT_R, POINT_G, POINT_B, LEG_ALPHA, +STEM_HALF, U0, U1, V0, V1, light, overlay); }
                    case NONE -> {}
                }
            } else {
                drawCrossXDashedAxisAligned(vc, mat, norm, face, EPS, U0, U1, V0, V1,
                        1.0f, 0.1f, 0.1f, X_ALPHA, X_THICK, light, overlay);
            }
        }
    }

    private static void draw4QuadrantsSelective(VertexConsumer vc, Matrix4f mat, Matrix3f norm,
                                                Direction face, float eps,
                                                float U0, float U1, float V0, float V1,
                                                byte[] c, int light, int overlay) {
        float I = QUAD_INSET;
        if (c[0] != QuickLinkColors.UNSET)
            drawQuadrant(vc, mat, norm, face, NORM_A0+I, NORM_A1-I, NORM_B0+I, NORM_B1-I, eps, U0, U1, V0, V1, c[0], light, overlay);
        if (c[1] != QuickLinkColors.UNSET)
            drawQuadrant(vc, mat, norm, face, NORM_B0+I, NORM_B1-I, NORM_B0+I, NORM_B1-I, eps, U0, U1, V0, V1, c[1], light, overlay);
        if (c[2] != QuickLinkColors.UNSET)
            drawQuadrant(vc, mat, norm, face, NORM_A0+I, NORM_A1-I, NORM_A0+I, NORM_A1-I, eps, U0, U1, V0, V1, c[2], light, overlay);
        if (c[3] != QuickLinkColors.UNSET)
            drawQuadrant(vc, mat, norm, face, NORM_B0+I, NORM_B1-I, NORM_A0+I, NORM_A1-I, eps, U0, U1, V0, V1, c[3], light, overlay);
    }

    private static void drawQuadrant(VertexConsumer vc, Matrix4f mat, Matrix3f norm,
                                     Direction face, float u0, float u1, float v0, float v1,
                                     float eps, float U0, float U1, float V0, float V1,
                                     byte dyeId, int light, int overlay) {
        int rgb = DyeColor.byId(dyeId & 0xFF).getFireworkColor();
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8)  & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        r = gammaLift(r, COLOR_GAMMA);
        g = gammaLift(g, COLOR_GAMMA);
        b = gammaLift(b, COLOR_GAMMA);
        drawRectOnFace(vc, mat, norm, face, eps, u0, u1, v0, v1, U0, U1, V0, V1, r, g, b, 1f, light, overlay);
    }

    private static void drawLeg(VertexConsumer vc, Matrix4f mat, Matrix3f norm,
                                Direction face, boolean outward,
                                float r, float g, float b, float alpha, float uOff,
                                float U0, float U1, float V0, float V1, int light, int overlay) {
        float c = 0.5f, cu = c + uOff, sh = STEM_HALF, ch = CONE_BASE_HALF, cl = CONE_LENGTH;

        int main, uAx, vAx; float sign;
        switch (face) {
            case SOUTH -> { main = 2; uAx = 0; vAx = 1; sign = +1f; }
            case NORTH -> { main = 2; uAx = 0; vAx = 1; sign = -1f; }
            case EAST  -> { main = 0; uAx = 2; vAx = 1; sign = +1f; }
            case WEST  -> { main = 0; uAx = 2; vAx = 1; sign = -1f; }
            case UP    -> { main = 1; uAx = 0; vAx = 2; sign = +1f; }
            case DOWN  -> { main = 1; uAx = 0; vAx = 2; sign = -1f; }
            default    -> { return; }
        }

        float inner = sign > 0 ? FACE_MAX : FACE_MIN, edge = sign > 0 ? 1.0f : 0.0f;
        float coneTip, coneBase, stemFrom, stemTo;
        if (outward) { coneTip = edge; coneBase = edge - sign * cl; stemFrom = inner; stemTo = coneBase; }
        else         { coneTip = inner; coneBase = inner + sign * cl; stemFrom = coneBase; stemTo = edge; }

        float stemMin = Math.min(stemFrom, stemTo), stemMax = Math.max(stemFrom, stemTo);
        if (stemMax > stemMin + 0.001f) {
            float[] s00 = p3(main,stemMin,uAx,cu-sh,vAx,c-sh), s01 = p3(main,stemMin,uAx,cu-sh,vAx,c+sh);
            float[] s10 = p3(main,stemMin,uAx,cu+sh,vAx,c-sh), s11 = p3(main,stemMin,uAx,cu+sh,vAx,c+sh);
            float[] e00 = p3(main,stemMax,uAx,cu-sh,vAx,c-sh), e01 = p3(main,stemMax,uAx,cu-sh,vAx,c+sh);
            float[] e10 = p3(main,stemMax,uAx,cu+sh,vAx,c-sh), e11 = p3(main,stemMax,uAx,cu+sh,vAx,c+sh);
            float[] nu = new float[3]; nu[uAx] = 1f;
            float[] nv = new float[3]; nv[vAx] = 1f;
            quad(vc,mat,norm,-nu[0],-nu[1],-nu[2], s00,s01,e01,e00, U0,V0,U1,V1, r,g,b,alpha, light,overlay);
            quad(vc,mat,norm, nu[0], nu[1], nu[2], s10,e10,e11,s11, U0,V0,U1,V1, r,g,b,alpha, light,overlay);
            quad(vc,mat,norm,-nv[0],-nv[1],-nv[2], s00,e00,e10,s10, U0,V0,U1,V1, r,g,b,alpha, light,overlay);
            quad(vc,mat,norm, nv[0], nv[1], nv[2], s01,s11,e11,e01, U0,V0,U1,V1, r,g,b,alpha, light,overlay);
        }

        float[] tip = p3(main,coneTip,uAx,cu,vAx,c);
        float[] BL  = p3(main,coneBase,uAx,cu-ch,vAx,c-ch), BR = p3(main,coneBase,uAx,cu+ch,vAx,c-ch);
        float[] TL  = p3(main,coneBase,uAx,cu-ch,vAx,c+ch), TR = p3(main,coneBase,uAx,cu+ch,vAx,c+ch);
        float[] nu = new float[3]; nu[uAx] = 1f;
        float[] nv = new float[3]; nv[vAx] = 1f;
        quad(vc,mat,norm,-nv[0],-nv[1],-nv[2], tip,tip,BL,BR, U0,V0,U1,V1, r,g,b,alpha, light,overlay);
        quad(vc,mat,norm, nv[0], nv[1], nv[2], tip,tip,TR,TL, U0,V0,U1,V1, r,g,b,alpha, light,overlay);
        quad(vc,mat,norm,-nu[0],-nu[1],-nu[2], tip,tip,TL,BL, U0,V0,U1,V1, r,g,b,alpha, light,overlay);
        quad(vc,mat,norm, nu[0], nu[1], nu[2], tip,tip,BR,TR, U0,V0,U1,V1, r,g,b,alpha, light,overlay);
    }

    private static float[] p3(int ax0, float v0, int ax1, float v1, int ax2, float v2) {
        float[] p = new float[3]; p[ax0] = v0; p[ax1] = v1; p[ax2] = v2; return p;
    }

    private static void drawCrossXDashedAxisAligned(VertexConsumer vc, Matrix4f mat, Matrix3f norm,
                                                    Direction face, float eps,
                                                    float U0, float U1, float V0, float V1,
                                                    float r, float g, float b, float a, float thick,
                                                    int light, int overlay) {
        drawCrossOnCell(vc, mat, norm, face, eps, U0, U1, V0, V1, NORM_A0, NORM_A1, NORM_A0, NORM_A1, r, g, b, a, thick, light, overlay);
        drawCrossOnCell(vc, mat, norm, face, eps, U0, U1, V0, V1, NORM_B0, NORM_B1, NORM_A0, NORM_A1, r, g, b, a, thick, light, overlay);
        drawCrossOnCell(vc, mat, norm, face, eps, U0, U1, V0, V1, NORM_A0, NORM_A1, NORM_B0, NORM_B1, r, g, b, a, thick, light, overlay);
        drawCrossOnCell(vc, mat, norm, face, eps, U0, U1, V0, V1, NORM_B0, NORM_B1, NORM_B0, NORM_B1, r, g, b, a, thick, light, overlay);
    }

    private static void drawCrossOnCell(VertexConsumer vc, Matrix4f mat, Matrix3f norm,
                                        Direction face, float eps,
                                        float U0, float U1, float V0, float V1,
                                        float cu0, float cu1, float cv0, float cv1,
                                        float r, float g, float b, float a, float thick,
                                        int light, int overlay) {
        drawDashedDiag(vc, mat, norm, face, eps, U0, U1, V0, V1, r, g, b, a, thick, light, overlay, cu0, cv0, cu1, cv1);
        drawDashedDiag(vc, mat, norm, face, eps, U0, U1, V0, V1, r, g, b, a, thick, light, overlay, cu1, cv0, cu0, cv1);
    }

    private static void drawDashedDiag(VertexConsumer vc, Matrix4f mat, Matrix3f norm,
                                       Direction face, float eps,
                                       float U0, float U1, float V0, float V1,
                                       float r, float g, float b, float a, float thick,
                                       int light, int overlay,
                                       float uA, float vA, float uB, float vB) {
        int segs = Math.max(2, X_SEGMENTS);
        float gapRatio = clamp01(X_GAP_RATIO);
        for (int i = 0; i < segs; i++) {
            float t0 = (float) i / segs, t1 = (float) (i + 1) / segs;
            float cut = (t1 - t0) * gapRatio * 0.5f; t0 += cut; t1 -= cut;
            if (t1 <= t0) continue;
            float su0 = lerp(uA, uB, t0), sv0 = lerp(vA, vB, t0);
            float su1 = lerp(uA, uB, t1), sv1 = lerp(vA, vB, t1);
            float pad = thick * 0.5f;
            float uu0 = clamp01(Math.min(su0,su1)-pad), uu1 = clamp01(Math.max(su0,su1)+pad);
            float vv0 = clamp01(Math.min(sv0,sv1)-pad), vv1 = clamp01(Math.max(sv0,sv1)+pad);
            drawRectOnFace(vc, mat, norm, face, eps, uu0, uu1, vv0, vv1, U0, U1, V0, V1, r, g, b, a, light, overlay);
        }
    }

    private static void drawRectOnFace(VertexConsumer vc, Matrix4f mat, Matrix3f norm,
                                       Direction face, float eps,
                                       float u0, float u1, float v0, float v1,
                                       float U0, float U1, float V0, float V1,
                                       float r, float g, float b, float a,
                                       int light, int overlay) {
        float su0 = toFaceCoord(Math.min(u0,u1)), su1 = toFaceCoord(Math.max(u0,u1));
        float sv0 = toFaceCoord(Math.min(v0,v1)), sv1 = toFaceCoord(Math.max(v0,v1));
        float nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();

        switch (face) {
            case SOUTH -> quad4(vc,mat,norm,nx,ny,nz,
                    su0,sv0,FACE_MAX+eps, su1,sv0,FACE_MAX+eps, su1,sv1,FACE_MAX+eps, su0,sv1,FACE_MAX+eps,
                    U0,V0,U1,V1, r,g,b,a, light,overlay);
            case NORTH -> quad4(vc,mat,norm,nx,ny,nz,
                    1f-su0,sv0,FACE_MIN-eps, 1f-su1,sv0,FACE_MIN-eps, 1f-su1,sv1,FACE_MIN-eps, 1f-su0,sv1,FACE_MIN-eps,
                    U0,V0,U1,V1, r,g,b,a, light,overlay);
            case EAST  -> quad4(vc,mat,norm,nx,ny,nz,
                    FACE_MAX+eps,sv0,1f-su0, FACE_MAX+eps,sv0,1f-su1, FACE_MAX+eps,sv1,1f-su1, FACE_MAX+eps,sv1,1f-su0,
                    U0,V0,U1,V1, r,g,b,a, light,overlay);
            case WEST  -> quad4(vc,mat,norm,nx,ny,nz,
                    FACE_MIN-eps,sv0,su0, FACE_MIN-eps,sv0,su1, FACE_MIN-eps,sv1,su1, FACE_MIN-eps,sv1,su0,
                    U0,V0,U1,V1, r,g,b,a, light,overlay);
            case UP    -> quad4(vc,mat,norm,nx,ny,nz,
                    su0,FACE_MAX+eps,1f-sv0, su1,FACE_MAX+eps,1f-sv0, su1,FACE_MAX+eps,1f-sv1, su0,FACE_MAX+eps,1f-sv1,
                    U0,V0,U1,V1, r,g,b,a, light,overlay);
            case DOWN  -> quad4(vc,mat,norm,nx,ny,nz,
                    su0,FACE_MIN-eps,sv0, su1,FACE_MIN-eps,sv0, su1,FACE_MIN-eps,sv1, su0,FACE_MIN-eps,sv1,
                    U0,V0,U1,V1, r,g,b,a, light,overlay);
        }
    }

    // overload taking float[] vertices
    private static void quad(VertexConsumer vc, Matrix4f mat, Matrix3f norm,
                             float nx, float ny, float nz,
                             float[] v1, float[] v2, float[] v3, float[] v4,
                             float U0, float V0, float U1, float V1,
                             float r, float g, float b, float a, int light, int overlay) {
        quad4(vc,mat,norm,nx,ny,nz,
                v1[0],v1[1],v1[2], v2[0],v2[1],v2[2], v3[0],v3[1],v3[2], v4[0],v4[1],v4[2],
                U0,V0,U1,V1, r,g,b,a, light,overlay);
    }

    private static void quad4(VertexConsumer vc, Matrix4f mat, Matrix3f norm,
                              float nx, float ny, float nz,
                              float x1, float y1, float z1,
                              float x2, float y2, float z2,
                              float x3, float y3, float z3,
                              float x4, float y4, float z4,
                              float U0, float V0, float U1, float V1,
                              float r, float g, float b, float a, int light, int overlay) {
        v(vc, mat, norm, x1, y1, z1, U0, V0, r, g, b, a, nx, ny, nz, light, overlay);
        v(vc, mat, norm, x2, y2, z2, U1, V0, r, g, b, a, nx, ny, nz, light, overlay);
        v(vc, mat, norm, x3, y3, z3, U1, V1, r, g, b, a, nx, ny, nz, light, overlay);
        v(vc, mat, norm, x4, y4, z4, U0, V1, r, g, b, a, nx, ny, nz, light, overlay);
    }

    // Forge 1.20.1 vertex API: .vertex(...).color(...).uv(...).overlayCoords(...).uv2(...).normal(...).endVertex()
    private static void v(VertexConsumer vc, Matrix4f mat, Matrix3f norm,
                          float x, float y, float z, float u, float vt,
                          float r, float g, float b, float a,
                          float nx, float ny, float nz, int light, int overlay) {
        vc.vertex(mat, x, y, z)
          .color(r, g, b, a)
          .uv(u, vt)
          .overlayCoords(overlay)
          .uv2(light)
          .normal(norm, nx, ny, nz)
          .endVertex();
    }

    private static int boostLight(int packedLight, int addBlock, int addSky) {
        int block = Math.min(15, LightTexture.block(packedLight) + addBlock);
        int sky   = Math.min(15, LightTexture.sky(packedLight)   + addSky);
        return LightTexture.pack(block, sky);
    }

    private static float gammaLift(float c, float gamma) {
        if (gamma >= 0.999f || c <= 0f || c >= 1f) return c;
        return (float) Math.pow(c, gamma);
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    private static float toFaceCoord(float t) { return FACE_MIN + (FACE_MAX - FACE_MIN) * clamp01(t); }
    private static float clamp01(float x) { return x < 0f ? 0f : (x > 1f ? 1f : x); }
}
