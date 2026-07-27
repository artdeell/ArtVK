# ArtVK
This Fabric mod for Minecraft 26.2 implements a Blaze3D backend that runs on Vulkan 1.1 devices. I got
rid of most of the big issues, however, it is still quite experimental.
## AI usage notice
AI was in heavy use during the initial development of this mod. Quite a bit of stuff doesn't make sense still,
so make sure to check out the code and send changes.
## Issues
- More RAM usage. Due to quirks of Minecraft resource loading, big descriptor sets are needed. However, this only affects the game startup
- Low performance on high render distances. The mod is limited to a render distance of 8 because the descriptor set allocator isn't mature enough for more.
## Future improvements
- [ ] Vulkan 1.0 compatibility
- [ ] Better descriptor set allocator
- [ ] Further code cleanup
- [ ] More?