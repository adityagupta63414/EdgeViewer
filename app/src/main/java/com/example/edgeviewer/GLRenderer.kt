package com.example.edgeviewer

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GLRenderer : GLSurfaceView.Renderer {

    // Texture ID
    private var textureId = 0

    // Frame buffer containing grayscale edge image (width * height bytes)
    private var frameBuffer: ByteBuffer? = null

    // Frame size
    private var frameWidth = 0
    private var frameHeight = 0

    // Last frame time for FPS counter
    private var lastTime = System.nanoTime()

    // Callback to update FPS text in MainActivity
    var fpsListener: ((Double) -> Unit)? = null

    // Vertex positions for full-screen quad
    private val vertexCoords = floatArrayOf(
        -1f, 1f,     // Top-left
        -1f, -1f,    // Bottom-left
        1f, 1f,      // Top-right
        1f, -1f      // Bottom-right
    )

    // Texture coordinates
    private val texCoords = floatArrayOf(
        0f, 0f,
        0f, 1f,
        1f, 0f,
        1f, 1f
    )

    private val vertexBuffer: FloatBuffer
    private val texBuffer: FloatBuffer

    // Shader program handle
    private var program = 0

    init {
        // Convert float arrays into OpenGL buffers
        vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexBuffer.put(vertexCoords).position(0)

        texBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        texBuffer.put(texCoords).position(0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // Create GL shader program
        program = createShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER)

        // Create one texture for grayscale frame
        textureId = createTexture()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        // Set GL viewport
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // FPS counter
        val now = System.nanoTime()
        val dt = (now - lastTime) / 1e9
        lastTime = now
        fpsListener?.invoke(if (dt > 0) 1.0 / dt else 0.0)

        frameBuffer?.let { buffer ->
            // Bind texture and upload grayscale image
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)

            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_LUMINANCE,
                frameWidth,
                frameHeight,
                0,
                GLES20.GL_LUMINANCE,
                GLES20.GL_UNSIGNED_BYTE,
                buffer
            )

            drawQuad()
        }
    }

    /** Updates a new processed frame from MainActivity */
    fun updateFrame(buffer: ByteBuffer, width: Int, height: Int) {
        if (frameWidth != width || frameHeight != height || frameBuffer == null) {
            frameWidth = width
            frameHeight = height
            frameBuffer = ByteBuffer.allocateDirect(width * height)
        }

        buffer.position(0)
        frameBuffer!!.position(0)
        frameBuffer!!.put(buffer)
        frameBuffer!!.position(0)
    }

    /** Renders a full-screen quad */
    private fun drawQuad() {
        GLES20.glUseProgram(program)

        val aPos = GLES20.glGetAttribLocation(program, "aPosition")
        val aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        val uTex = GLES20.glGetUniformLocation(program, "uTexture")

        // Vertex positions
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        // Texture coordinates
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        // Bind texture
        GLES20.glUniform1i(uTex, 0)

        // Draw the quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    /** Creates a GL texture for grayscale rendering */
    private fun createTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        return tex[0]
    }

    /** Loads and compiles shaders */
    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    /** Links the vertex and fragment shader into a program */
    private fun createShaderProgram(vs: String, fs: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vs)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fs)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        return program
    }

    companion object {

        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;

            // Sample the grayscale edge image and display it
            void main() {
                float gray = texture2D(uTexture, vTexCoord).r;
                gl_FragColor = vec4(gray, gray, gray, 1.0);
            }
        """
    }
}
