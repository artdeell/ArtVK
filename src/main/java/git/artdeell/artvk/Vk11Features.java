package git.artdeell.artvk;

// Yes, it sucks, but we can't store emulated features inside real DeviceFeatures
public record Vk11Features(
        boolean multiDrawIndirect
) {}
