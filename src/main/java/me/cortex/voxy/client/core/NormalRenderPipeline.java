package me.cortex.voxy.client.core;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.util.FogCompat;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.ARBComputeShader.glDispatchCompute;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.glBindImageTexture;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL14.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL15.GL_READ_WRITE;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL43.GL_DEPTH_STENCIL_TEXTURE_MODE;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;
import static org.lwjgl.opengl.GL45C.glTextureParameterf;

public class NormalRenderPipeline extends AbstractRenderPipeline {
    private GlTexture colourTex;
    private GlTexture colourSSAOTex;
    private final GlFramebuffer fbSSAO = new GlFramebuffer();

    private final FullscreenBlit finalBlit;

    private final Shader ssaoCompute = Shader.make()
            .add(ShaderType.COMPUTE, "voxy:post/ssao.comp")
            .compile();

    protected NormalRenderPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        super(nodeManager, nodeCleaner, traversal, frexSupplier, false);
        // USE_ENV_FOG is always compiled in: even with the user's environmental fog switched off,
        // the composite must still reproduce "required" fog (underwater/lava/blindness/darkness),
        // otherwise LOD terrain draws clear over fog the vanilla pass is applying.
        this.finalBlit = new FullscreenBlit("voxy:post/blit_texture_depth_cutout.frag",
                a->a.define("USE_ENV_FOG").define("EMIT_COLOUR"));
    }

    @Override
    protected int setup(Viewport<?> viewport, int sourceFB, int srcWidth, int srcHeight) {
        if (this.colourTex == null || this.colourTex.getHeight() != viewport.height || this.colourTex.getWidth() != viewport.width) {
            if (this.colourTex != null) {
                this.colourTex.free();
                this.colourSSAOTex.free();
            }
            this.fb.resize(viewport.width, viewport.height);

            this.colourTex = new GlTexture().store(GL_RGBA8, 1, viewport.width, viewport.height);
            this.colourSSAOTex = new GlTexture().store(GL_RGBA8, 1, viewport.width, viewport.height);

            this.fb.framebuffer.bind(GL_COLOR_ATTACHMENT0, this.colourTex).verify();
            this.fbSSAO.bind(this.fb.getDepthAttachmentType(), this.fb.getDepthTex()).bind(GL_COLOR_ATTACHMENT0, this.colourSSAOTex).verify();


            glTextureParameterf(this.colourTex.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourTex.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourSSAOTex.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourSSAOTex.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameterf(this.fb.getDepthTex().id, GL_DEPTH_STENCIL_TEXTURE_MODE, GL_DEPTH_COMPONENT);
        }

        this.initDepthStencil(sourceFB, this.fb.framebuffer.id, viewport.width, viewport.height, viewport.width, viewport.height);

        return this.fb.getDepthTex().id;
    }

    @Override
    protected void postOpaquePreTranslucent(Viewport<?> viewport) {
        this.ssaoCompute.bind();
        try (var stack = MemoryStack.stackPush()) {
            long ptr = stack.nmalloc(4*4*4);
            viewport.MVP.getToAddress(ptr);
            nglUniformMatrix4fv(3, 1, false, ptr);//MVP
            viewport.MVP.invert(new Matrix4f()).getToAddress(ptr);
            nglUniformMatrix4fv(4, 1, false, ptr);//invMVP
        }


        glBindImageTexture(0, this.colourSSAOTex.id, 0, false,0, GL_READ_WRITE, GL_RGBA8);
        glBindTextureUnit(1, this.fb.getDepthTex().id);
        glBindTextureUnit(2, this.colourTex.id);

        glDispatchCompute((viewport.width+31)/32, (viewport.height+31)/32, 1);

        glBindFramebuffer(GL_FRAMEBUFFER, this.fbSSAO.id);
    }

    @Override
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        this.finalBlit.bind();
        float fogStart;
        float fogEnd;
        float[] fogColor;
        boolean requiredFog;
        boolean useFog;
        if (FogCompat.BETTER_FOG) {
            // Better Fog owns the frame's fog (MixinFogRenderer stands down entirely). Sample it
            // LIVE at composite time - the exact values the near terrain was just drawn with -
            // but do NOT apply that line verbatim: a near-fog line saturates just past its own
            // end and would bury every LOD beyond it in solid fog. Instead the LOD gets a second
            // line through two pinned points (the contract the old micvoxy bridge proved out):
            //   - the handoff (vanilla render distance): same opacity as the near fog there,
            //     so the two meet without a step and the seam stays invisible;
            //   - the LOD fog edge: full opacity, so the world's edge is buried, not a rim.
            // When the near fog already saturates before the handoff (underwater, blindness,
            // dense weather), atHandoff hits 1, the slope dies, and the near line is applied
            // verbatim - which is exactly right: nothing should be visible past that fog.
            float nearStart = RenderSystem.getShaderFogStart();
            float nearEnd = RenderSystem.getShaderFogEnd();
            float span = nearEnd - nearStart;
            fogColor = RenderSystem.getShaderFogColor();
            requiredFog = true;
            // A pushed-to-infinity or degenerate range means "no fog this frame".
            useFog = Float.isFinite(span) && span > 1.0e-4f && Float.isFinite(nearStart) && nearEnd < 1.0e7f;
            fogStart = nearStart;
            fogEnd = nearEnd;
            if (useFog) {
                float edge = VoxyConfig.CONFIG.getLodRenderDistanceBlocks()
                        * (VoxyConfig.CONFIG.fogDistancePercent / 100.0f);
                float handoff = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f;
                // Needs real room between handoff and edge, or the curve is a cliff.
                if (edge > handoff + 16.0f) {
                    float atHandoff = Math.clamp((handoff - nearStart) / span, 0.0f, 1.0f);
                    float slope = (1.0f - atHandoff) / (edge - handoff);
                    if (Float.isFinite(slope) && slope > 0.0f) {
                        // Same line, expressed as the (start, end) pair the shader consumes.
                        fogStart = handoff - atHandoff / slope;
                        fogEnd = fogStart + 1.0f / slope;
                    }
                }
            }
        } else {
            var vrs = IGetVoxyRenderSystem.getNullable();
            fogStart = vrs != null ? vrs.getCapturedFogStart() : RenderSystem.getShaderFogStart();
            fogEnd = vrs != null ? vrs.getCapturedFogEnd()   : RenderSystem.getShaderFogEnd();
            fogColor = vrs != null ? vrs.getCapturedFogColor() : RenderSystem.getShaderFogColor();
            requiredFog = vrs != null && vrs.isCapturedFogRequired();

            boolean useOptionalFog = VoxyConfig.CONFIG.useEnvironmentalFog
                    && VoxyConfig.CONFIG.fogIntensity > 0.0f;
            if (!requiredFog && useOptionalFog) {
                fogEnd = VoxyConfig.CONFIG.getLodRenderDistanceBlocks()
                        * (VoxyConfig.CONFIG.fogDistancePercent / 100.0f);
                fogStart = fogEnd * (VoxyConfig.CONFIG.fogStartPercent / 100.0f);
            }
            float fogRange = Math.abs(fogEnd - fogStart);
            useFog = requiredFog
                    ? fogRange > 1.0e-4f
                    : useOptionalFog && fogRange > 1.0f;
        }

        if (useFog) {
            glUniform2f(4, fogStart, fogEnd);
            glUniform4f(5, fogColor[0], fogColor[1], fogColor[2], 1.0f);
            glUniform1i(6, RenderSystem.getShaderFogShape().getIndex());
            // Required fog (the camera's medium, an effect, or fog another mod owns) is applied
            // exactly as it was computed for the near terrain: full intensity, no density bend.
            // Optional distance fog uses the user's intensity/density instead.
            glUniform1f(7, requiredFog ? 1.0f : Math.clamp(VoxyConfig.CONFIG.fogIntensity, 0.0f, 1.0f));
            glUniform1f(8, requiredFog ? 0.0f : Math.clamp(VoxyConfig.CONFIG.fogDensity, 0.0f, 1.0f));
            // Always the smoothstep curve: Sodium runs every fog type through one
            // smoothstep(fogStart, fogEnd, dist) (assets/sodium/shaders/include/fog.glsl), so a
            // linear ramp here would part company with the near terrain mid-fade and show a seam.
            glUniform1i(9, 0);
        } else {
            glUniform2f(4, 0, 0);
            glUniform4f(5, 0, 0, 0, 0);
            glUniform1i(6, 0);
            glUniform1f(7, 0);
            glUniform1f(8, 0);
            glUniform1i(9, 0);
        }

        glBindTextureUnit(3, this.colourSSAOTex.id);

        //Do alpha blending

        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        AbstractRenderPipeline.transformBlitDepth(this.finalBlit, this.fb.getDepthTex().id, sourceFrameBuffer, viewport, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView));
        glDisable(GL_BLEND);
        //glBlitNamedFramebuffer(this.fbSSAO.id, sourceFrameBuffer, 0,0, viewport.width, viewport.height, 0,0, viewport.width, viewport.height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    }

    @Override
    public void setupAndBindOpaque(Viewport<?> viewport) {
        this.fb.bind();
    }

    @Override
    public void setupAndBindTranslucent(Viewport<?> viewport) {
        glBindFramebuffer(GL_FRAMEBUFFER, this.fbSSAO.id);
    }

    @Override
    public void free() {
        this.finalBlit.delete();
        this.ssaoCompute.free();
        this.fbSSAO.free();
        if (this.colourTex != null) {
            this.colourTex.free();
            this.colourSSAOTex.free();
        }
        super.free0();
    }
}
