# ArtVK
This Fabric mod for Minecraft 26.2 implements a Blaze3D backend that runs on Vulkan 1.1 devices. I got
rid of most of the big issues, however, it is still quite experimental.
## AI usage notice
AI was in heavy use during the initial development of this mod. Quite a bit of stuff doesn't make sense still,
so make sure to check out the code and send changes.
## Issues
- Insane RAM usage. Due to quirks of Minecraft resource loading, *huge* descriptor sets are needed
- Low performance on high render distances. Same as above.
- Some rendering glitches at high framerates
## Future improvements
- [ ] Vulkan 1.0 compatibility
- [ ] Renderpass restarting
- [ ] More?