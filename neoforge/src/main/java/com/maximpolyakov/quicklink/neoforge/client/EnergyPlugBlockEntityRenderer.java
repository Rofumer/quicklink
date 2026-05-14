package com.maximpolyakov.quicklink.neoforge.client;

import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.neoforge.blockentity.EnergyPlugBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public class EnergyPlugBlockEntityRenderer implements BlockEntityRenderer<EnergyPlugBlockEntity, EnergyPlugBlockEntityRenderer.PlugRenderState> {

    private static final float EPS        = 0.001f;
    private static final float FACE_MIN   = 6f / 16f;
    private static final float FACE_MAX   = 10f / 16f;
    private static final float NORM_A0    = 0f;
    private static final float NORM_A1    = 0.45f;
    private static final float NORM_B0    = 0.55f;
    private static final float NORM_B1    = 1f;
    private static final float QUAD_INSET = 0.10f;

    private static final Identifier WHITE_TEX =
            Identifier.fromNamespaceAndPath("minecraft", "block/white_wool");

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

    public EnergyPlugBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    public static class PlugRenderState extends BlockEntityRenderState {
        byte[][] colors   = new byte[6][4];
        boolean[] allUnset = new boolean[6];
        boolean[] active   = new boolean[6];
        boolean[] hasPlug  = new boolean[6];
        boolean[] hasPoint = new boolean[6];
        boolean[] enabled  = new boolean[6];
    }

    @Override
    public PlugRenderState createRenderState() { return new PlugRenderState(); }

    @Override
    public void extractRenderState(EnergyPlugBlockEntity be, PlugRenderState state,
                                   float partialTick, Vec3 cameraPos,
                                   ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderState.extractBase(be, state, crumbling);
        for (Direction face : Direction.values()) {
            int i = face.ordinal();
            QuickLinkColors c = be.getColors(face);
            state.colors[i]   = c.toArray();
            state.allUnset[i] = c.isAllUnset();
            EnergyPlugBlockEntity.SideRole role = be.getRole(face);
            state.active[i]   = role != EnergyPlugBlockEntity.SideRole.NONE;
            state.hasPlug[i]  = role == EnergyPlugBlockEntity.SideRole.PLUG || role == EnergyPlugBlockEntity.SideRole.BOTH;
            state.hasPoint[i] = role == EnergyPlugBlockEntity.SideRole.POINT || role == EnergyPlugBlockEntity.SideRole.BOTH;
            state.enabled[i]  = be.isSideEnabled(face);
        }
    }

    @Override
    public void submit(PlugRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState cameraState) {
        TextureAtlasSprite sprite = Minecraft.getInstance().getAtlasManager()
                .get(new SpriteId(TextureAtlas.LOCATION_BLOCKS, WHITE_TEX));
        if (sprite == null) return;

        float U0 = sprite.getU0(), U1 = sprite.getU1();
        float V0 = sprite.getV0(), V1 = sprite.getV1();
        int overlay = OverlayTexture.NO_OVERLAY;
        int light   = boostLight(state.lightCoords, LIGHT_BOOST_BLOCK, LIGHT_BOOST_SKY);

        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS), (pose, vc) -> {
            Matrix4f mat = pose.pose();
            for (Direction face : Direction.values()) {
                int i = face.ordinal();
                if (!state.allUnset[i])
                    draw4QuadrantsSelective(vc, pose, mat, face, EPS, U0, U1, V0, V1, state.colors[i], light, overlay);
            }
            for (Direction face : Direction.values()) {
                int i = face.ordinal();
                if (!state.active[i]) continue;
                if (state.enabled[i]) {
                    if (state.hasPlug[i] && state.hasPoint[i]) {
                        drawLeg(vc,pose,mat,face,true, PLUG_R, PLUG_G, PLUG_B, LEG_ALPHA,-STEM_HALF,U0,U1,V0,V1,light,overlay);
                        drawLeg(vc,pose,mat,face,false,POINT_R,POINT_G,POINT_B,LEG_ALPHA,+STEM_HALF,U0,U1,V0,V1,light,overlay);
                    } else if (state.hasPlug[i]) {
                        drawLeg(vc,pose,mat,face,true, PLUG_R, PLUG_G, PLUG_B, LEG_ALPHA,0f,U0,U1,V0,V1,light,overlay);
                    } else {
                        drawLeg(vc,pose,mat,face,false,POINT_R,POINT_G,POINT_B,LEG_ALPHA,0f,U0,U1,V0,V1,light,overlay);
                    }
                } else {
                    drawCrossXDashedAxisAligned(vc,pose,mat,face,EPS,U0,U1,V0,V1,1.0f,0.1f,0.1f,X_ALPHA,X_THICK,light,overlay);
                }
            }
        });
    }

    private static void draw4QuadrantsSelective(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                                Direction face, float eps,
                                                float U0, float U1, float V0, float V1,
                                                byte[] c, int light, int overlay) {
        float I = QUAD_INSET;
        if (c[0] != QuickLinkColors.UNSET) drawQuadrant(vc,pose,mat,face,NORM_A0+I,NORM_A1-I,NORM_B0+I,NORM_B1-I,eps,U0,U1,V0,V1,c[0],light,overlay);
        if (c[1] != QuickLinkColors.UNSET) drawQuadrant(vc,pose,mat,face,NORM_B0+I,NORM_B1-I,NORM_B0+I,NORM_B1-I,eps,U0,U1,V0,V1,c[1],light,overlay);
        if (c[2] != QuickLinkColors.UNSET) drawQuadrant(vc,pose,mat,face,NORM_A0+I,NORM_A1-I,NORM_A0+I,NORM_A1-I,eps,U0,U1,V0,V1,c[2],light,overlay);
        if (c[3] != QuickLinkColors.UNSET) drawQuadrant(vc,pose,mat,face,NORM_B0+I,NORM_B1-I,NORM_A0+I,NORM_A1-I,eps,U0,U1,V0,V1,c[3],light,overlay);
    }

    private static void drawQuadrant(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                     Direction face, float u0, float u1, float v0, float v1, float eps,
                                     float U0, float U1, float V0, float V1,
                                     byte dyeId, int light, int overlay) {
        int rgb = DyeColor.byId(dyeId & 0xFF).getFireworkColor();
        drawRectOnFace(vc,pose,mat,face,eps,u0,u1,v0,v1,U0,U1,V0,V1,
                gammaLift(((rgb>>16)&0xFF)/255f,COLOR_GAMMA),
                gammaLift(((rgb>> 8)&0xFF)/255f,COLOR_GAMMA),
                gammaLift(( rgb     &0xFF)/255f,COLOR_GAMMA),
                1f,light,overlay);
    }

    private static void drawLeg(VertexConsumer vc, PoseStack.Pose pose, Matrix4f mat,
                                Direction face, boolean outward,
                                float r, float g, float b, float alpha, float uOff,
                                float U0, float U1, float V0, float V1, int light, int overlay) {
        float c=0.5f, cu=c+uOff, sh=STEM_HALF, ch=CONE_BASE_HALF, cl=CONE_LENGTH;
        int main, uAx, vAx; float sign;
        switch (face) {
            case SOUTH->{main=2;uAx=0;vAx=1;sign=+1f;}
            case NORTH->{main=2;uAx=0;vAx=1;sign=-1f;}
            case EAST ->{main=0;uAx=2;vAx=1;sign=+1f;}
            case WEST ->{main=0;uAx=2;vAx=1;sign=-1f;}
            case UP   ->{main=1;uAx=0;vAx=2;sign=+1f;}
            case DOWN ->{main=1;uAx=0;vAx=2;sign=-1f;}
            default->{return;}
        }
        float inner=sign>0?FACE_MAX:FACE_MIN, edge=sign>0?1.0f:0.0f;
        float coneTip, coneBase, stemFrom, stemTo;
        if (outward){coneTip=edge;coneBase=edge-sign*cl;stemFrom=inner;stemTo=coneBase;}
        else        {coneTip=inner;coneBase=inner+sign*cl;stemFrom=coneBase;stemTo=edge;}
        float stemMin=Math.min(stemFrom,stemTo), stemMax=Math.max(stemFrom,stemTo);
        if (stemMax>stemMin+0.001f) {
            float[] s00=p3(main,stemMin,uAx,cu-sh,vAx,c-sh),s01=p3(main,stemMin,uAx,cu-sh,vAx,c+sh);
            float[] s10=p3(main,stemMin,uAx,cu+sh,vAx,c-sh),s11=p3(main,stemMin,uAx,cu+sh,vAx,c+sh);
            float[] e00=p3(main,stemMax,uAx,cu-sh,vAx,c-sh),e01=p3(main,stemMax,uAx,cu-sh,vAx,c+sh);
            float[] e10=p3(main,stemMax,uAx,cu+sh,vAx,c-sh),e11=p3(main,stemMax,uAx,cu+sh,vAx,c+sh);
            float[] nu=new float[3];nu[uAx]=1f; float[] nv=new float[3];nv[vAx]=1f;
            quad(vc,pose,mat,-nu[0],-nu[1],-nu[2],s00,s01,e01,e00,U0,V0,U1,V1,r,g,b,alpha,light,overlay);
            quad(vc,pose,mat, nu[0], nu[1], nu[2],s10,e10,e11,s11,U0,V0,U1,V1,r,g,b,alpha,light,overlay);
            quad(vc,pose,mat,-nv[0],-nv[1],-nv[2],s00,e00,e10,s10,U0,V0,U1,V1,r,g,b,alpha,light,overlay);
            quad(vc,pose,mat, nv[0], nv[1], nv[2],s01,s11,e11,e01,U0,V0,U1,V1,r,g,b,alpha,light,overlay);
        }
        float[] tip=p3(main,coneTip,uAx,cu,vAx,c);
        float[] BL=p3(main,coneBase,uAx,cu-ch,vAx,c-ch),BR=p3(main,coneBase,uAx,cu+ch,vAx,c-ch);
        float[] TL=p3(main,coneBase,uAx,cu-ch,vAx,c+ch),TR=p3(main,coneBase,uAx,cu+ch,vAx,c+ch);
        float[] nu=new float[3];nu[uAx]=1f; float[] nv=new float[3];nv[vAx]=1f;
        quad(vc,pose,mat,-nv[0],-nv[1],-nv[2],tip,tip,BL,BR,U0,V0,U1,V1,r,g,b,alpha,light,overlay);
        quad(vc,pose,mat, nv[0], nv[1], nv[2],tip,tip,TR,TL,U0,V0,U1,V1,r,g,b,alpha,light,overlay);
        quad(vc,pose,mat,-nu[0],-nu[1],-nu[2],tip,tip,TL,BL,U0,V0,U1,V1,r,g,b,alpha,light,overlay);
        quad(vc,pose,mat, nu[0], nu[1], nu[2],tip,tip,BR,TR,U0,V0,U1,V1,r,g,b,alpha,light,overlay);
    }

    private static float[] p3(int ax0,float v0,int ax1,float v1,int ax2,float v2){float[] p=new float[3];p[ax0]=v0;p[ax1]=v1;p[ax2]=v2;return p;}

    private static void drawCrossXDashedAxisAligned(VertexConsumer vc,PoseStack.Pose pose,Matrix4f mat,
                                                    Direction face,float eps,float U0,float U1,float V0,float V1,
                                                    float r,float g,float b,float a,float thick,int light,int overlay){
        drawCrossOnCell(vc,pose,mat,face,eps,U0,U1,V0,V1,NORM_A0,NORM_A1,NORM_A0,NORM_A1,r,g,b,a,thick,light,overlay);
        drawCrossOnCell(vc,pose,mat,face,eps,U0,U1,V0,V1,NORM_B0,NORM_B1,NORM_A0,NORM_A1,r,g,b,a,thick,light,overlay);
        drawCrossOnCell(vc,pose,mat,face,eps,U0,U1,V0,V1,NORM_A0,NORM_A1,NORM_B0,NORM_B1,r,g,b,a,thick,light,overlay);
        drawCrossOnCell(vc,pose,mat,face,eps,U0,U1,V0,V1,NORM_B0,NORM_B1,NORM_B0,NORM_B1,r,g,b,a,thick,light,overlay);
    }

    private static void drawCrossOnCell(VertexConsumer vc,PoseStack.Pose pose,Matrix4f mat,
                                        Direction face,float eps,float U0,float U1,float V0,float V1,
                                        float cu0,float cu1,float cv0,float cv1,
                                        float r,float g,float b,float a,float thick,int light,int overlay){
        drawDashedDiag(vc,pose,mat,face,eps,U0,U1,V0,V1,r,g,b,a,thick,light,overlay,cu0,cv0,cu1,cv1);
        drawDashedDiag(vc,pose,mat,face,eps,U0,U1,V0,V1,r,g,b,a,thick,light,overlay,cu1,cv0,cu0,cv1);
    }

    private static void drawDashedDiag(VertexConsumer vc,PoseStack.Pose pose,Matrix4f mat,
                                       Direction face,float eps,float U0,float U1,float V0,float V1,
                                       float r,float g,float b,float a,float thick,int light,int overlay,
                                       float uA,float vA,float uB,float vB){
        float gapRatio=clamp01(X_GAP_RATIO);
        for(int i=0;i<Math.max(2,X_SEGMENTS);i++){
            float t0=(float)i/X_SEGMENTS,t1=(float)(i+1)/X_SEGMENTS;
            float cut=(t1-t0)*gapRatio*0.5f;t0+=cut;t1-=cut;
            if(t1<=t0)continue;
            float su0=lerp(uA,uB,t0),sv0=lerp(vA,vB,t0),su1=lerp(uA,uB,t1),sv1=lerp(vA,vB,t1);
            float pad=thick*0.5f;
            drawRectOnFace(vc,pose,mat,face,eps,
                    clamp01(Math.min(su0,su1)-pad),clamp01(Math.max(su0,su1)+pad),
                    clamp01(Math.min(sv0,sv1)-pad),clamp01(Math.max(sv0,sv1)+pad),
                    U0,U1,V0,V1,r,g,b,a,light,overlay);
        }
    }

    private static void drawRectOnFace(VertexConsumer vc,PoseStack.Pose pose,Matrix4f mat,
                                       Direction face,float eps,
                                       float u0,float u1,float v0,float v1,
                                       float U0,float U1,float V0,float V1,
                                       float r,float g,float b,float a,int light,int overlay){
        float su0=toFaceCoord(Math.min(u0,u1)),su1=toFaceCoord(Math.max(u0,u1));
        float sv0=toFaceCoord(Math.min(v0,v1)),sv1=toFaceCoord(Math.max(v0,v1));
        float nx=face.getStepX(),ny=face.getStepY(),nz=face.getStepZ();
        switch(face){
            case SOUTH->quad(vc,pose,mat,nx,ny,nz,p3v(su0,sv0,FACE_MAX+eps),p3v(su1,sv0,FACE_MAX+eps),p3v(su1,sv1,FACE_MAX+eps),p3v(su0,sv1,FACE_MAX+eps),U0,V0,U1,V1,r,g,b,a,light,overlay);
            case NORTH->quad(vc,pose,mat,nx,ny,nz,p3v(1f-su0,sv0,FACE_MIN-eps),p3v(1f-su1,sv0,FACE_MIN-eps),p3v(1f-su1,sv1,FACE_MIN-eps),p3v(1f-su0,sv1,FACE_MIN-eps),U0,V0,U1,V1,r,g,b,a,light,overlay);
            case EAST ->quad(vc,pose,mat,nx,ny,nz,p3v(FACE_MAX+eps,sv0,1f-su0),p3v(FACE_MAX+eps,sv0,1f-su1),p3v(FACE_MAX+eps,sv1,1f-su1),p3v(FACE_MAX+eps,sv1,1f-su0),U0,V0,U1,V1,r,g,b,a,light,overlay);
            case WEST ->quad(vc,pose,mat,nx,ny,nz,p3v(FACE_MIN-eps,sv0,su0),p3v(FACE_MIN-eps,sv0,su1),p3v(FACE_MIN-eps,sv1,su1),p3v(FACE_MIN-eps,sv1,su0),U0,V0,U1,V1,r,g,b,a,light,overlay);
            case UP   ->quad(vc,pose,mat,nx,ny,nz,p3v(su0,FACE_MAX+eps,1f-sv0),p3v(su1,FACE_MAX+eps,1f-sv0),p3v(su1,FACE_MAX+eps,1f-sv1),p3v(su0,FACE_MAX+eps,1f-sv1),U0,V0,U1,V1,r,g,b,a,light,overlay);
            case DOWN ->quad(vc,pose,mat,nx,ny,nz,p3v(su0,FACE_MIN-eps,sv0),p3v(su1,FACE_MIN-eps,sv0),p3v(su1,FACE_MIN-eps,sv1),p3v(su0,FACE_MIN-eps,sv1),U0,V0,U1,V1,r,g,b,a,light,overlay);
        }
    }

    private static float[] p3v(float x,float y,float z){return new float[]{x,y,z};}

    private static void quad(VertexConsumer vc,PoseStack.Pose pose,Matrix4f mat,
                             float nx,float ny,float nz,
                             float[] p1,float[] p2,float[] p3,float[] p4,
                             float U0,float V0,float U1,float V1,
                             float r,float g,float b,float a,int light,int overlay){
        v(vc,pose,mat,p1[0],p1[1],p1[2],U0,V0,r,g,b,a,nx,ny,nz,light,overlay);
        v(vc,pose,mat,p2[0],p2[1],p2[2],U1,V0,r,g,b,a,nx,ny,nz,light,overlay);
        v(vc,pose,mat,p3[0],p3[1],p3[2],U1,V1,r,g,b,a,nx,ny,nz,light,overlay);
        v(vc,pose,mat,p4[0],p4[1],p4[2],U0,V1,r,g,b,a,nx,ny,nz,light,overlay);
    }

    private static void v(VertexConsumer vc,PoseStack.Pose pose,Matrix4f mat,
                          float x,float y,float z,float u,float vt,
                          float r,float g,float b,float a,
                          float nx,float ny,float nz,int light,int overlay){
        vc.addVertex(mat,x,y,z).setColor(r,g,b,a).setUv(u,vt).setOverlay(overlay).setLight(light).setNormal(pose,nx,ny,nz);
    }

    private static int boostLight(int packed,int addBlock,int addSky){
        return LightCoordsUtil.pack(Math.min(15,LightCoordsUtil.block(packed)+addBlock),
                                   Math.min(15,LightCoordsUtil.sky(packed)+addSky));
    }
    private static float gammaLift(float c,float gamma){if(gamma>=0.999f||c<=0f)return c;return c>=1f?1f:(float)Math.pow(c,gamma);}
    private static float lerp(float a,float b,float t){return a+(b-a)*t;}
    private static float toFaceCoord(float t){return FACE_MIN+(FACE_MAX-FACE_MIN)*clamp01(t);}
    private static float clamp01(float x){return x<0f?0f:(x>1f?1f:x);}
}
