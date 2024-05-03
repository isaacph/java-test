package com.isaacph.render;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import com.isaacph.util.MathUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL20.*;

public class TileGridRenderer {

    public static class ByteGrid implements Serializable {

    public static final int SIZE = 16;

    public byte[] data = new byte[SIZE * SIZE];
    public int x, y;

    public ByteGrid(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public ByteGrid(JSONObject obj) {
        x = obj.getInt("x");
        y = obj.getInt("y");
        JSONArray d = obj.getJSONArray("data");
        if(d.length() != data.length) throw new RuntimeException("ByteGrid at " + x + ", " + y + " has block data of wrong size " + d.length());
        for(int i = 0; i < d.length(); ++i) {
            data[i] = (byte) d.getInt(i);
        }
    }

    public byte get(int x, int y) {
        return data[x * SIZE + y];
    }
    public void set(byte b, int x, int y) {
        data[x * SIZE + y] = b;
    }

    public JSONObject toJSON() {
        JSONObject obj = new JSONObject();
        obj.put("x", x);
        obj.put("y", y);
        JSONArray arr = new JSONArray();
        for(byte b : data) {
            arr.put(b);
        }
        obj.put("data", arr);
        return obj;
    }

    public static class Group implements Serializable {
        public Map<Vector2i, ByteGrid> map = new HashMap<>();

        public Group() {

        }

        public ByteGrid setTile(byte b, int x, int y) {
            Vector2i p = getGridIndex(x, y);
            ByteGrid f = map.get(p);
            if(f == null) {
                f = new ByteGrid(p.x, p.y);
                map.put(p, f);
            }
            f.set(b, ((x % SIZE) + SIZE) % SIZE, ((y % SIZE) + SIZE) % SIZE);
            return f;
        }

        public ByteGrid makeTileGrid(int x, int y) {
            Vector2i p = getGridIndex(x, y);
            ByteGrid f = map.get(p);
            if(f == null) {
                f = new ByteGrid(p.x, p.y);
                map.put(p, f);
            }
            return f;
        }
        public byte getTile(int x, int y) {
            ByteGrid f = map.get(getGridIndex(x, y));
            if(f == null) return 0;
            return f.get(((x % SIZE) + SIZE) % SIZE, ((y % SIZE) + SIZE) % SIZE);
        }
        public byte getTile(float x, float y) {
            return getTile(MathUtil.floor(x), MathUtil.floor(y));
        }
        public ByteGrid setTile(byte b, float x, float y) {
            return setTile(b, MathUtil.floor(x), MathUtil.floor(y));
        }
        public Vector2i getGridIndex(int x, int y) {
            return new Vector2i((int) Math.floor((double) x / SIZE), (int) Math.floor((double) y / SIZE));
        }

        public JSONObject toJSON() {
            JSONObject obj = new JSONObject();
            for(Vector2i key : map.keySet()) {
                String id = "(" + key.x + ", " + key.y + ")";
                obj.put(id, map.get(key).toJSON());
            }
            return obj;
        }

        public void fromJSON(JSONObject obj) {
            for(String key : obj.keySet()) {
                String[] args = key.split(",");
                if(!args[0].trim().startsWith("(") ||
                        !args[1].trim().endsWith(")") ||
                        args[0].trim().length() <= 1 ||
                        args[1].trim().length() <= 1)
                    throw new RuntimeException("Wrong ByteGrid JSON key format: " + key);

                int x, y;
                try {
                    x = Integer.parseInt(args[0].trim().substring(1));
                    y = Integer.parseInt(args[1].trim().substring(0, args[1].trim().length() - 1));
                } catch(NumberFormatException e) {
                    throw new RuntimeException("Could not parse ByteGrid JSON key as ints: " + key);
                }
                map.put(new Vector2i(x, y), new ByteGrid(obj.getJSONObject(key)));
            }
        }
    }
}


    private int shader;
    private int shaderMatrix;
    private int shaderColor;
    private int shaderSampler1;
    private int shaderSampler2;
    private int shaderVertScale;
    private int shaderNumTiles;
    private int shaderTexMorph;
    private int shaderSamplerTile;
    private int shaderLineWidth;
    private int shaderTextureOffset;
    private int shaderTextureScale;

    private int selectShader;
    private int selectShaderMatrix;
    private int selectShaderEmptyColor;
    private int selectShaderFillColor;
    private int selectShaderVertScale;
    private int selectShaderNumTiles;
    private int selectShaderTexMorph;
    private int selectShaderSamplerTile;
    private int selectShaderLineWidth;

    /**
     * Every tile's visual height / width
     */
    public static final float TILE_RATIO = 0.6f;
    public static final float TILE_WIDTH = 1.0f;

//    private static final float[] squareCoords = {
//        -0.5f, -0.5f, 0.0f, 0.0f,
//        -0.5f, 0.5f, 0.0f, 1.0f,
//        0.5f, 0.5f, 1.0f, 1.0f,
//        0.5f, 0.5f, 1.0f, 1.0f,
//        0.5f, -0.5f, 1.0f, 0.0f,
//        -0.5f, -0.5f, 0.0f, 0.0f
//    };

    private static class GridInfo {
        public int texture;

        public void cleanUp() {
            glDeleteTextures(texture);
        }
    }

    private Texture grass, grass2;
    private final Map<Vector2i, GridInfo> gridMap = new HashMap<>();
    private final Map<Vector2i, GridInfo> selectGridMap = new HashMap<>();
    private static final Matrix4f TEX_MORPH =
        new Matrix4f().scale(-(float) Math.sqrt(2), TILE_RATIO * (float) Math.sqrt(2), 0)
            .rotate(45.0f * (float) Math.PI / 180.0f, 0, 0, 1);
    private int vbo;
    private int vboSelect;

    private float scale = 1.0f;

    public TileGridRenderer() {
        {
            int vertex = Shaders.createShader("texturev.glsl", GL_VERTEX_SHADER);
            int fragment = Shaders.createShader("gridf.glsl", GL_FRAGMENT_SHADER);
            shader = glCreateProgram();
            glAttachShader(shader, vertex);
            glAttachShader(shader, fragment);
            glBindAttribLocation(shader, Shaders.Attribute.POSITION.position, "position");
            glBindAttribLocation(shader, Shaders.Attribute.TEXTURE.position, "tex");
            glLinkProgram(shader);
            Shaders.checkLinking(shader);
            glUseProgram(shader);
            shaderMatrix = glGetUniformLocation(shader, "matrix");
            shaderColor = glGetUniformLocation(shader, "color");
            shaderSampler1 = glGetUniformLocation(shader, "sampler1");
            shaderSampler2 = glGetUniformLocation(shader, "sampler2");
            shaderSamplerTile = glGetUniformLocation(shader, "samplerTile");
            shaderVertScale = glGetUniformLocation(shader, "vertScale");
            shaderNumTiles = glGetUniformLocation(shader, "numTiles");
            shaderTexMorph = glGetUniformLocation(shader, "texMorph");
            shaderLineWidth = glGetUniformLocation(shader, "lineWidth");
            shaderTextureOffset = glGetUniformLocation(shader, "textureOffset");
            shaderTextureScale = glGetUniformLocation(shader, "textureScale");
            glDeleteShader(vertex);
            glDeleteShader(fragment);
            Shaders.checkGLError("Shader link tile grid " + shader);
        }
        {
            int vertex = Shaders.createShader("texturev.glsl", GL_VERTEX_SHADER);
            int fragment = Shaders.createShader("selectf.glsl", GL_FRAGMENT_SHADER);
            selectShader = glCreateProgram();
            glAttachShader(selectShader, vertex);
            glAttachShader(selectShader, fragment);
            glBindAttribLocation(selectShader, Shaders.Attribute.POSITION.position, "position");
            glBindAttribLocation(selectShader, Shaders.Attribute.TEXTURE.position, "tex");
            glLinkProgram(selectShader);
            Shaders.checkLinking(selectShader);
            glUseProgram(selectShader);
            selectShaderMatrix = glGetUniformLocation(selectShader, "matrix");
            selectShaderFillColor = glGetUniformLocation(selectShader, "fillColor");
            selectShaderEmptyColor = glGetUniformLocation(selectShader, "emptyColor");
            selectShaderSamplerTile = glGetUniformLocation(selectShader, "samplerTile");
            selectShaderVertScale = glGetUniformLocation(selectShader, "vertScale");
            selectShaderNumTiles = glGetUniformLocation(selectShader, "numTiles");
            selectShaderTexMorph = glGetUniformLocation(selectShader, "texMorph");
            selectShaderLineWidth = glGetUniformLocation(selectShader, "lineWidth");
            glDeleteShader(vertex);
            glDeleteShader(fragment);
            Shaders.checkGLError("Shader link tile grid " + shader);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(4 * 6);
            Vector2f c = new Vector2f(0, 0);
            Vector2f s = new Vector2f(ByteGrid.SIZE / (float) Math.sqrt(2), TILE_RATIO * ByteGrid.SIZE / (float) Math.sqrt(2));
            buffer.put(new float[] {
                c.x, c.y - s.y, -0.5f, -0.5f,
                c.x - s.x, c.y, +0.5f, -0.5f,
                c.x, c.y + s.y, +0.5f, +0.5f,
                c.x, c.y + s.y, +0.5f, +0.5f,
                c.x + s.x, c.y, -0.5f, +0.5f,
                c.x, c.y - s.y, -0.5f, -0.5f,
            });
            buffer.flip();
            this.vbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW);
            Shaders.checkGLError("Tile ByteGrid VBO init");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            float overestRatio = 21.0f / 20.0f;
            FloatBuffer buffer = stack.mallocFloat(4 * 6);
            Vector2f c = new Vector2f(0);
            Vector2f s = new Vector2f(ByteGrid.SIZE / (float) Math.sqrt(2), TILE_RATIO * ByteGrid.SIZE / (float) Math.sqrt(2)).mul(overestRatio);
            Vector2f t = new Vector2f(0.5f, 0.5f).mul(overestRatio);
            buffer.put(new float[] {
                    c.x, c.y - s.y, -t.x, -t.y,
                    c.x - s.x, c.y, +t.x, -t.y,
                    c.x, c.y + s.y, +t.x, +t.y,
                    c.x, c.y + s.y, +t.x, +t.y,
                    c.x + s.x, c.y, -t.x, +t.y,
                    c.x, c.y - s.y, -t.x, -t.y,
            });
            buffer.flip();
            this.vboSelect = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vboSelect);
            glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW);
            Shaders.checkGLError("Tile ByteGrid VBO init");
        }

        grass = Texture.makeTexture("grass.png", new Texture.Settings(GL_REPEAT, GL_LINEAR));
        grass2 = Texture.makeTexture("mock grass 2.png", new Texture.Settings(GL_REPEAT, GL_LINEAR));
        scale = 2.0f;
    }

    public void build(ByteGrid grid) {
        try(MemoryStack stack = MemoryStack.stackPush()) {
            GridInfo data = gridMap.get(new Vector2i(grid.x, grid.y));
            ByteBuffer buffer = stack.malloc(ByteGrid.SIZE * ByteGrid.SIZE);
            buffer.put(grid.data);
            buffer.flip();
            if (data == null) {
                data = new GridInfo();
                data.texture = glGenTextures();
            }
            glBindTexture(GL_TEXTURE_2D, data.texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RED, ByteGrid.SIZE, ByteGrid.SIZE, 0, GL_RED, GL_BYTE, buffer);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            gridMap.put(new Vector2i(grid.x, grid.y), data);
        }
        Shaders.checkGLError("Tile grid build " + grid.x + ", " + grid.y);
    }

    public void clear() {
        for(GridInfo data : gridMap.values()) {
            glDeleteTextures(data.texture);
        }
        gridMap.clear();
    }

    public void buildSelect(List<ByteGrid> gridsToBuild) {
        selectGridMap.clear();
        for(ByteGrid grid : gridsToBuild) {
            try(MemoryStack stack = MemoryStack.stackPush()) {
                GridInfo data = selectGridMap.get(new Vector2i(grid.x, grid.y));
                ByteBuffer buffer = stack.malloc(ByteGrid.SIZE * ByteGrid.SIZE);
                buffer.put(grid.data);
                buffer.flip();
                if(data == null) {
                    data = new GridInfo();
                    data.texture = glGenTextures();
                }
                glBindTexture(GL_TEXTURE_2D, data.texture);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RED, ByteGrid.SIZE, ByteGrid.SIZE, 0, GL_RED, GL_BYTE, buffer);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                selectGridMap.put(new Vector2i(grid.x, grid.y), data);
            }
            Shaders.checkGLError("Tile grid build " + grid.x + ", " + grid.y);
        }
    }

    public void draw(Matrix4f matrix, Vector4f color, float scale) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(16);
            glActiveTexture(GL_TEXTURE1);
            grass.bind();
            glActiveTexture(GL_TEXTURE2);
            grass2.bind();
            glUseProgram(shader);
            glUniform4f(shaderColor, color.x, color.y, color.z, color.w);
            glUniform1i(shaderSamplerTile, 0);
            glUniform1i(shaderSampler1, 1);
            glUniform1i(shaderSampler2, 2);
            glUniform1f(shaderVertScale, TILE_RATIO);
            glUniform1i(shaderNumTiles, ByteGrid.SIZE);
            glUniform1f(shaderLineWidth, 1.0f / (ByteGrid.SIZE) / (TILE_WIDTH) / scale * 0.0f);
            glUniform1f(shaderTextureScale, this.scale);

//            Vector2f v2 = Camera.worldToViewSpace(new Vector2f(16.0f, 16.0f).sub(new Vector2f(0.0f, 0.0f))).mul(1.0f / (float) Math.sqrt(2.0f) / ByteGrid.SIZE);
//            System.out.println(v2.x + ", " + v2.y);

            glUniformMatrix4fv(shaderTexMorph, false, TEX_MORPH.get(buffer));
            buffer.clear();
            glActiveTexture(GL_TEXTURE0);
            for (Vector2i key : gridMap.keySet()) {
                GridInfo info = gridMap.get(key);
                glBindTexture(GL_TEXTURE_2D, info.texture);
                glBindBuffer(GL_ARRAY_BUFFER, vbo);
                glEnableVertexAttribArray(Shaders.Attribute.POSITION.position);
                glVertexAttribPointer(Shaders.Attribute.POSITION.position,
                    2, GL_FLOAT, false, 4 * 4, 0);
                glEnableVertexAttribArray(Shaders.Attribute.TEXTURE.position);
                glVertexAttribPointer(Shaders.Attribute.TEXTURE.position,
                    2, GL_FLOAT, false, 4 * 4, 4 * 2);
                Vector2f v = Camera.worldToViewSpace(new Vector2f((key.x + 0.5f) * ByteGrid.SIZE,
                    (key.y + 0.5f) * ByteGrid.SIZE));
                glUniformMatrix4fv(shaderMatrix, false, new Matrix4f(matrix)
                    .translate(v.x, v.y, 0).get(buffer));
                Vector2f textureOffset = Camera.worldToViewSpace(new Vector2f(key).mul(ByteGrid.SIZE).sub(new Vector2f(0.0f, 0.0f))).mul(1.0f / (float) Math.sqrt(2) / ByteGrid.SIZE);
//                System.out.println(textureOffset.x + ", " + textureOffset.y);
                textureOffset = new Vector2f((key.x + key.y), (key.x + key.y) * TILE_RATIO);
//                Vector2f textureOffset = new Vector2f();
                glUniform2f(shaderTextureOffset, textureOffset.x, textureOffset.y);
                glDrawArrays(GL_TRIANGLES, 0, 6);
                glDisableVertexAttribArray(Shaders.Attribute.POSITION.position);
                glDisableVertexAttribArray(Shaders.Attribute.TEXTURE.position);
                buffer.clear();
            }
        }
    }

    public void drawSelect(Matrix4f matrix, float scale) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(16);
            glUseProgram(selectShader);
            glUniform4f(selectShaderEmptyColor, 0, 0, 0, 0);
            glUniform4f(selectShaderFillColor, 1.0f, 1.0f, 1.0f, 0.2f);
            glUniform1i(selectShaderSamplerTile, 0);
            glUniform1f(selectShaderVertScale, TILE_RATIO);
            glUniform1i(selectShaderNumTiles, ByteGrid.SIZE);
            glUniform1f(selectShaderLineWidth, 1.0f / (ByteGrid.SIZE) / (TILE_WIDTH) / scale * 2.0f);

//            Vector2f v2 = Camera.worldToViewSpace(new Vector2f(16.0f, 16.0f).sub(new Vector2f(0.0f, 0.0f))).mul(1.0f / (float) Math.sqrt(2.0f) / ByteGrid.SIZE);
//            System.out.println(v2.x + ", " + v2.y);

            glUniformMatrix4fv(selectShaderTexMorph, false, TEX_MORPH.get(buffer));
            buffer.clear();
            glActiveTexture(GL_TEXTURE0);
            for (Vector2i key : selectGridMap.keySet()) {
                GridInfo info = selectGridMap.get(key);
                glBindTexture(GL_TEXTURE_2D, info.texture);
                glBindBuffer(GL_ARRAY_BUFFER, vboSelect);
                glEnableVertexAttribArray(Shaders.Attribute.POSITION.position);
                glVertexAttribPointer(Shaders.Attribute.POSITION.position,
                        2, GL_FLOAT, false, 4 * 4, 0);
                glEnableVertexAttribArray(Shaders.Attribute.TEXTURE.position);
                glVertexAttribPointer(Shaders.Attribute.TEXTURE.position,
                        2, GL_FLOAT, false, 4 * 4, 4 * 2);
                Vector2f v = Camera.worldToViewSpace(new Vector2f((key.x + 0.5f) * ByteGrid.SIZE,
                        (key.y + 0.5f) * ByteGrid.SIZE));
                glUniformMatrix4fv(selectShaderMatrix, false, new Matrix4f(matrix)
                        .translate(v.x, v.y, 0).get(buffer));
                glDrawArrays(GL_TRIANGLES, 0, 6);
                glDisableVertexAttribArray(Shaders.Attribute.POSITION.position);
                glDisableVertexAttribArray(Shaders.Attribute.TEXTURE.position);
                buffer.clear();
            }
        }
    }

    public void cleanUp() {
        glDeleteProgram(shader);
        glDeleteProgram(selectShader);
        glDeleteBuffers(vbo);
        glDeleteBuffers(vboSelect);
        for(GridInfo info : gridMap.values()) {
            info.cleanUp();
        }
        for(GridInfo info : selectGridMap.values()) {
            info.cleanUp();
        }
    }
}
