package git.artdeell.artvk;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.ShaderDefines;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK11;

@Environment(EnvType.CLIENT)
public class Vk11GlslCompiler implements AutoCloseable {
	private final long shaderCompiler = Shaderc.shaderc_compiler_initialize();
	private final long shaderOptions = Shaderc.shaderc_compile_options_initialize();
	private final ShaderDefines globalDefines;

	public Vk11GlslCompiler() {
		Shaderc.shaderc_compile_options_set_target_env(this.shaderOptions, Shaderc.shaderc_target_env_vulkan, VK11.VK_API_VERSION_1_1);
		Shaderc.shaderc_compile_options_set_auto_bind_uniforms(this.shaderOptions, true);
		Shaderc.shaderc_compile_options_set_auto_map_locations(this.shaderOptions, true);
		Shaderc.shaderc_compile_options_set_generate_debug_info(this.shaderOptions);
		Shaderc.shaderc_compile_options_set_optimization_level(this.shaderOptions, Shaderc.shaderc_optimization_level_zero);
		this.globalDefines = ShaderDefines.builder().define("gl_VertexID", "gl_VertexIndex").define("gl_InstanceID", "gl_InstanceIndex").build();
	}

	public Vk11IntermediaryShaderModule createIntermediary(final String filename, String source, final ShaderType type) throws ShaderCompileException {
		source = GlslPreprocessor.injectDefines(source, this.globalDefines);
		int shaderType = type == ShaderType.FRAGMENT ? Shaderc.shaderc_glsl_fragment_shader : Shaderc.shaderc_glsl_vertex_shader;
		ByteBuffer sourceBuffer = MemoryUtil.memUTF8(source, false);
		ByteBuffer filenameBuffer = MemoryUtil.memUTF8(filename);
		ByteBuffer entrypointBuffer = MemoryUtil.memUTF8("main");
		long result = Shaderc.shaderc_compile_into_spv(this.shaderCompiler, sourceBuffer, shaderType, filenameBuffer, entrypointBuffer, this.shaderOptions);

		try {
			int status = Shaderc.shaderc_result_get_compilation_status(result);
			if (status != 0) {
				throw new ShaderCompileException("Couldn't parse GLSL: " + Shaderc.shaderc_result_get_error_message(result));
			}

			ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
            if(spirv == null) {
                throw new ShaderCompileException("Couldn't get compiled spir-v from shaderc");
            }

			ByteBuffer copy = MemoryUtil.memCalloc(spirv.remaining());
			MemoryUtil.memCopy(spirv, copy);
			return Vk11IntermediaryShaderModule.createFromSpirv(filename, copy);
		} finally {
			Shaderc.shaderc_result_release(result);
			MemoryUtil.memFree(entrypointBuffer);
			MemoryUtil.memFree(filenameBuffer);
			MemoryUtil.memFree(sourceBuffer);
		}
	}

	public Vk11GlslCompiler.CompiledModules compile(
		final Vk11Device device, final RenderPipeline pipeline, final Vk11IntermediaryShaderModule vertex, final Vk11IntermediaryShaderModule fragment
	) throws ShaderCompileException {
		String pipelineName = pipeline.getLocation().toString();
		List<Vk11BindGroupLayout.Entry> entries = new ArrayList<>();
		addToBindGroup(entries, vertex, pipeline);
		addToBindGroup(entries, fragment, pipeline);
		List<String> vertexOutputNames = new ArrayList<>();

		for (SpvVariable output : vertex.outputs()) {
			vertexOutputNames.add(output.name());
		}

		List<String> vertexInputNames = new ArrayList<>();

		for (VertexFormat vertexFormat : pipeline.getVertexFormatBindings()) {
			if (vertexFormat != null) {
				for (VertexFormatElement attribute : vertexFormat.getElements()) {
					vertexInputNames.add(attribute.name());
				}
			}
		}

		vertex.rebind(vertexInputNames, entries);
		fragment.rebind(vertexOutputNames, entries);
		long vertexId = vertex.createVulkanShaderModule(device);
		long fragmentId = fragment.createVulkanShaderModule(device);
		Vk11BindGroupLayout layout = Vk11BindGroupLayout.create(device, entries, pipelineName);
		return new Vk11GlslCompiler.CompiledModules(vertexId, fragmentId, layout);
	}

	@Override
	public void close() {
		Shaderc.shaderc_compile_options_release(this.shaderOptions);
		Shaderc.shaderc_compiler_release(this.shaderCompiler);
	}

	private static void addToBindGroup(final List<Vk11BindGroupLayout.Entry> entries, final Vk11IntermediaryShaderModule shader, final RenderPipeline pipeline) throws ShaderCompileException {
		for (SpvUniformBuffer buffer : shader.uniformBuffers()) {
			String name = buffer.name();
			Optional<BindGroupLayout.UniformDescription> uniformDescription = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())
				.stream()
				.filter(d -> d.name().equals(name))
				.findFirst();
			if (uniformDescription.isEmpty()) {
				throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
			}

			if (entries.stream().noneMatch(e -> e.type() == Vk11BindGroupLayout.Vk11BindGroupEntryType.UNIFORM_BUFFER && e.name().equals(name))) {
				entries.add(new Vk11BindGroupLayout.Entry(Vk11BindGroupLayout.Vk11BindGroupEntryType.UNIFORM_BUFFER, name, null));
			}
		}

		for (SpvSampler sampler : shader.samplers()) {
			String name = sampler.name();
			Optional<BindGroupLayout.UniformDescription> uniformDescription = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())
				.stream()
				.filter(d -> d.name().equals(name))
				.findFirst();
			if (uniformDescription.isPresent()) {
				if (sampler.dimensions() != Spv.SpvDimBuffer) {
					throw new ShaderCompileException("UTB (" + name + ") must have type of SpvDimBuffer");
				}

				if (entries.stream().noneMatch(e -> e.type() == Vk11BindGroupLayout.Vk11BindGroupEntryType.TEXEL_BUFFER && e.name().equals(name))) {
					entries.add(new Vk11BindGroupLayout.Entry(Vk11BindGroupLayout.Vk11BindGroupEntryType.TEXEL_BUFFER, name, uniformDescription.get().gpuFormat()));
				}
			} else {
				if (BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts()).stream().noneMatch(name::equals)) {
					throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
				}

				if (sampler.dimensions() != Spv.SpvDim2D && sampler.dimensions() != Spv.SpvDimCube) {
					throw new ShaderCompileException("Sampled texture (" + name + ") must have type of SpvDim2D or SpvDimCube");
				}

				if (entries.stream().noneMatch(e -> e.type() == Vk11BindGroupLayout.Vk11BindGroupEntryType.SAMPLED_IMAGE && e.name().equals(name))) {
					entries.add(new Vk11BindGroupLayout.Entry(Vk11BindGroupLayout.Vk11BindGroupEntryType.SAMPLED_IMAGE, name, null));
				}
			}
		}
	}

	@Environment(EnvType.CLIENT)
	public record CompiledModules(long vertex, long fragment, Vk11BindGroupLayout layout) {
	}
}
