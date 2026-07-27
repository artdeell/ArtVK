package git.artdeell.artvk;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;

import java.nio.LongBuffer;

public class Vk11FramebufferCache {
    private final Vk11Device device;
    private final Long2ObjectOpenHashMap<Framebuffer> framebufferCache = new Long2ObjectOpenHashMap<>();

    public Vk11FramebufferCache(Vk11Device device) {
        this.device = device;
    }

    private static long computeFramebufferKey(final long renderPass, final int width, final int height, final Vk11GpuTextureView[] views) {
        long key = renderPass;
        key = key * 31 + width;
        key = key * 31 + height;
        for (Vk11GpuTextureView view : views) {
            key = key * 31 + view.vkImageView();
        }
        return key;
    }

    // In the future, when i maybe implement render pass cache cleanup, framebuffers would also be their dependents
    public long getOrCreateFramebuffer(
            final long renderPass,
            final int width,
            final int height,
            final Vk11GpuTextureView[] imageViews
    ) {
        long key = computeFramebufferKey(renderPass, width, height, imageViews);
        Framebuffer cached = framebufferCache.get(key);
        if(cached != null) return cached.pointer;

        Framebuffer framebuffer = new Framebuffer(key, renderPass, width, height, imageViews);
        for(Vk11GpuTextureView view : imageViews) {
            view.addDependent(framebuffer);
        }
        this.framebufferCache.put(key, framebuffer);
        return framebuffer.pointer;
    }

    public void destroy() {
        for(Framebuffer framebuffer : framebufferCache.values()) framebuffer.destroy();
    }

    private class Framebuffer implements Destroyable, Dependent {
        protected boolean isClosed = false;
        protected final long myKey;
        protected final long pointer;

        public Framebuffer(long myKey, long renderPass, int width, int height, Vk11GpuTextureView[] views) {
            this.myKey = myKey;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack)
                        .sType$Default()
                        .renderPass(renderPass)
                        .width(width)
                        .height(height)
                        .layers(1);

                LongBuffer viewsBuffer = stack.callocLong(views.length);
                for(int i = 0; i < views.length; i++) {
                    Vk11GpuTextureView view = views[i];
                    viewsBuffer.put(i, view.vkImageView());
                    view.addDependent(this);
                }

                framebufferInfo.attachmentCount(views.length);
                framebufferInfo.pAttachments(viewsBuffer);

                LongBuffer pointer = stack.callocLong(1);
                Vk11Utils.crashIfFailure(VK10.vkCreateFramebuffer(device.vkDevice(), framebufferInfo, null, pointer), "Failed to create VkFramebuffer");
                this.pointer = pointer.get(0);
            }
        }

        @Override
        public void destroy() {
            VK10.vkDestroyFramebuffer(device.vkDevice(), pointer, null);
        }

        @Override
        public void parentClosed() {
            if(isClosed) return;
            isClosed = true;
            framebufferCache.remove(myKey);
            device.createCommandEncoder().queueForDestroy(this);
        }
    }
}
