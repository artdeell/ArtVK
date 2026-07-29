package git.artdeell.artvk;

// This provides access to features/properties from extensions
public class Vk11ExtensionProperties {
    private long maxMemoryAllocationSize;
    private boolean shaderDrawParameters;
    private boolean multiDraw;
    private boolean vertexAttributeDivisor;

    public boolean vertexAttributeDivisor() {
        return vertexAttributeDivisor;
    }

    public void setVertexAttributeDivisor(boolean vertexAttributeDivisor) {
        this.vertexAttributeDivisor = vertexAttributeDivisor;
    }

    public boolean multiDraw() {
        return multiDraw;
    }

    public void setMultiDraw(boolean multiDraw) {
        this.multiDraw = multiDraw;
    }

    public boolean shaderDrawParameters() {
        return shaderDrawParameters;
    }

    public void setShaderDrawParameters(boolean shaderDrawParameters) {
        this.shaderDrawParameters = shaderDrawParameters;
    }

    public long maxMemoryAllocationSize() {
        return maxMemoryAllocationSize;
    }

    public void setMaxMemoryAllocationSize(long maxMemoryAllocationSize) {
        this.maxMemoryAllocationSize = maxMemoryAllocationSize;
    }
}
