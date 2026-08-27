package halo.engine.cli

import halo.engine.HaloCompiler
import halo.engine.PythonSpritePacker
import kotlinx.serialization.json.Json
import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: halo-engine compile <scene.json> -o <out.lua>")
        return
    }

    when (args[0]) {
        "compile" -> compile(args.sliceArray(1 until args.size))
        else -> {
            println("Unknown command: ${args[0]}")
            println("Usage: halo-engine compile <scene.json> -o <out.lua>")
        }
    }
}

private fun compile(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: halo-engine compile <scene.json> -o <out.lua>")
        return
    }

    val sceneFile = File(args[0])
    val outIndex = args.indexOf("-o")
    val outFile = if (outIndex != -1 && outIndex + 1 < args.size) File(args[outIndex + 1]) else File("out.lua")

    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val element = json.parseToJsonElement(sceneFile.readText())

    val pythonDir = File(System.getProperty("user.dir"), "python").absolutePath
    val packer = PythonSpritePacker(pythonExe = detectPython(), pythonPath = pythonDir)
    val compiler = HaloCompiler(packer)
    val lua = compiler.compile(element)

    outFile.parentFile?.mkdirs()
    outFile.writeText(lua)
    println("Compiled ${sceneFile.path} -> ${outFile.path} (${lua.length} chars)")
}

private fun detectPython(): String {
    return if (ProcessBuilder("python", "--version").start().waitFor() == 0) "python" else "python3"
}
