package com.isaacph;

import org.joml.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;

import com.isaacph.render.BoxRenderer;
import com.isaacph.render.Camera;
import com.isaacph.render.Font;
import com.isaacph.render.Shaders;
import com.isaacph.util.MathUtil;

import java.util.*;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

public class Main {

    // The window handle
    private long window;
    private BoxRenderer boxRenderer;

    // this state info will likely be moved
    public int screenWidth = 800, screenHeight = 600;
    public final Vector2f mousePosition = new Vector2f();
    public final Vector2i mouseWorldPosition = new Vector2i();
    public final Vector2f mouseViewPosition = new Vector2f();

    public GameTime gameTime;
    public Camera camera;
    public Font font;

    public Chatbox chatbox;

    private Mode mode = Mode.PLAY;

    enum Mode {
        PLAY, EDIT
    }

    public void run() {
        init();
        loop();
        cleanUp();

        // Free the window callbacks and destroy the window
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);

        // Terminate GLFW and free the error callback
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }

    private void init() {
        // Setup an error callback. The default implementation
        // will print the error message in System.err.
        GLFWErrorCallback.createPrint(System.err).set();

        // Initialize GLFW. Most GLFW functions will not work before doing this.
        if ( !glfwInit() )
            throw new IllegalStateException("Unable to initialize GLFW");

        // Configure GLFW
        glfwDefaultWindowHints(); // optional, the current window hints are already the default
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // the window will stay hidden after creation
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE); // the window will be resizable

        // Create the window
        window = glfwCreateWindow(screenWidth, screenHeight, "Unnamed Game", NULL, NULL);
        if ( window == NULL )
            throw new RuntimeException("Failed to create the GLFW window");

        // Make the OpenGL context current
        glfwMakeContextCurrent(window);
        // Enable v-sync
        glfwSwapInterval(1);

        // Make the window visible
        glfwShowWindow(window);
        glfwFocusWindow(window);
        // This line is critical for LWJGL's interoperation with GLFW's
        // OpenGL context, or any context that is managed externally.
        // LWJGL detects the context that is current in the current thread,
        // creates the GLCapabilities instance and makes the OpenGL
        // bindings available for use.
        GL.createCapabilities();

        // Set the background color
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        // Enable blending (properly handling alpha values)
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glfwSetWindowSizeCallback(window, (win, w, h) -> {
            windowResize(w, h);
        });
        glfwSetMouseButtonCallback(window, ((window1, button, action, mods) -> {
            mouseButton(button, action, mods);
        }));
        glfwSetKeyCallback(window, ((window1, key, scancode, action, mods) -> {
            keyboardButton(key, scancode, action, mods);
        }));
        glfwSetCharCallback(window, (win, codepoint) -> {
            if(chatbox.focus) {
                chatbox.typing.append((char) codepoint);
            }
        });

        this.boxRenderer = new BoxRenderer();
        this.gameTime = new GameTime(window);
        this.camera = new Camera(gameTime, window);

        this.font = new Font("font.ttf", 24, 512, 512);
        this.chatbox = new Chatbox(font, boxRenderer, gameTime);

        windowResize(screenWidth, screenHeight);
    }

    private void windowResize(int width, int height) {
        glViewport(0, 0, width, height);
        screenWidth = width;
        screenHeight = height;

        camera.windowResize(width, height);
    }

    private void pollMousePosition() {
        double[] mx = new double[1], my = new double[1];
        glfwGetCursorPos(window, mx, my);
        mousePosition.x = (float) mx[0];
        mousePosition.y = (float) my[0];
        Vector2f vpos = camera.screenToViewSpace(mousePosition);
        Vector2f pos = Camera.viewToWorldSpace(vpos);
        mouseWorldPosition.set(MathUtil.floor(pos.x), MathUtil.floor(pos.y));
        mouseViewPosition.set(vpos);
    }

    private void mouseButton(int button, int action, int mods) {
        if(mode == Mode.PLAY) {
            if(button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
            }
        } else if(mode == Mode.EDIT) {
            if(button == GLFW_MOUSE_BUTTON_RIGHT && action == GLFW_PRESS && glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) {
            }
        }
    }

    private void keyboardButton(int key, int scancode, int action, int mods) {
        if(chatbox.focus) {
            if(key == GLFW_KEY_ENTER && action == GLFW_PRESS) {
                if (!chatbox.send()) {
                    chatbox.disable();
                }
            }
            if(key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
                chatbox.disable();
                if(chatbox.typing.length() > 0) {
                    chatbox.typing.delete(0, chatbox.typing.length());
                }
            } else if(key == GLFW_KEY_BACKSPACE && action > 0) {
                if(chatbox.typing.length() > 0) {
                    chatbox.typing.deleteCharAt(chatbox.typing.length() - 1);
                }
            }
            if(key == GLFW_KEY_UP && action == GLFW_PRESS) {
                chatbox.prevCommand();
            }
            else if(key == GLFW_KEY_DOWN && action == GLFW_PRESS) {
                chatbox.nextCommand();
            }
        } else {
            if(key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
            } else if(key == GLFW_KEY_ENTER && action == GLFW_PRESS) {
                chatbox.enable();
            } else if(key == GLFW_KEY_SLASH && action == GLFW_PRESS) {
                chatbox.enable();
                // the / gets captured and appended anyways by the other callback
                // if(chatbox.typing.isEmpty()) {
                //     chatbox.typing.append('/');
                // }
            } else if(mode == Mode.PLAY) {
                // stuff related to playing the game
            }
        }
    }

    private void loop() {

        // Run the rendering loop until the user has attempted to close
        // the window or has pressed the ESCAPE key.
        while ( !glfwWindowShouldClose(window) ) {

            gameTime.update();

            // Poll for window events. Invokes window callbacks
            pollMousePosition();
            glfwPollEvents();
            if(!chatbox.focus) camera.move();

            if(mode == Mode.EDIT) {
            } else if(mode == Mode.PLAY) {
            }

            // all updates go here
            chatbox.update();
            for(String cmd : chatbox.commands) {
                try {
                    if(cmd.startsWith("/")) {
                        String[] args = cmd.substring(1).split("\\s");
                        args[0] = args[0].toLowerCase();
                        if(args[0].equals("test")) {
                            chatbox.println("Testing!");
                        } else if(args[0].equals("exit")) {
                            glfwSetWindowShouldClose(this.window, true);
                        } else if(args[0].equals("edit")) {
                            mode = Mode.EDIT;
                            chatbox.println("Editing enabled");
                        } else if(args[0].equals("play")) {
                            mode = Mode.PLAY;
                            chatbox.println("Gameplay enabled");
                        } else {
                            chatbox.println("Unknown command!");
                        }
                    } else {
                        //connection.queueSend(new ChatMessage(cmd));
                        chatbox.println("Chat not implemented yet");
                    }
                } catch(Exception e) {
                    chatbox.println("Error processing command:");
                    chatbox.println(e.getMessage());
                    e.printStackTrace();
                }
            }
            chatbox.prevCommands.addAll(chatbox.commands);
            chatbox.commands.clear();

            glClear(GL_COLOR_BUFFER_BIT); // clear the framebuffer

            // everything drawing goes here
            camera.updateView();

            boxRenderer.draw(new Matrix4f(camera.getProjView()).translate(mouseViewPosition.x, mouseViewPosition.y, 0).scale(0.25f),
                    new Vector4f(0.5f));

            chatbox.draw(camera.getProjection());

            glfwSwapBuffers(window); // swap the color buffers, rendering what was drawn to the screen
        }

        Shaders.checkGLError("End main loop");
    }

    public void cleanUp() {
        boxRenderer.cleanUp();
    }

}

