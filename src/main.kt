package org.jetbrains.dokka

import com.sampullara.cli.*
import com.intellij.openapi.util.*
import org.jetbrains.kotlin.cli.common.messages.*
import org.jetbrains.kotlin.cli.common.arguments.*
import org.jetbrains.kotlin.utils.PathUtil
import java.io.File
import org.jetbrains.kotlin.name.FqName

class DokkaArguments {
    Argument(value = "src", description = "Source file or directory (allows many paths separated by the system path separator)")
    ValueDescription("<path>")
    public var src: String = ""

    Argument(value = "srcLink", description = "Mapping between a source directory and a Web site for browsing the code")
    ValueDescription("<path>=<url>[#lineSuffix]")
    public var srcLink: String = ""

    Argument(value = "include", description = "Markdown files to load (allows many paths separated by the system path separator)")
    ValueDescription("<path>")
    public var include: String = ""

    Argument(value = "samples", description = "Source root for samples")
    ValueDescription("<path>")
    public var samples: String = ""

    Argument(value = "output", description = "Output directory path")
    ValueDescription("<path>")
    public var outputDir: String = "out/doc/"

    Argument(value = "format", description = "Output format (text, html, markdown, jekyll, kotlin-website)")
    ValueDescription("<name>")
    public var outputFormat: String = "html"

    Argument(value = "module", description = "Name of the documentation module")
    ValueDescription("<name>")
    public var moduleName: String = ""

    Argument(value = "classpath", description = "Classpath for symbol resolution")
    ValueDescription("<path>")
    public var classpath: String = ""

}

class SourceLinkDefinition(val path: String, val url: String, val lineSuffix: String?)

private fun parseSourceLinkDefinition(srcLink: String): SourceLinkDefinition {
    val (path, urlAndLine) = srcLink.split('=')
    return SourceLinkDefinition(File(path).getAbsolutePath(),
            urlAndLine.substringBefore("#"),
            urlAndLine.substringAfter("#", "").let { if (it.isEmpty()) null else "#" + it })
}

public fun main(args: Array<String>) {
    val arguments = DokkaArguments()
    val freeArgs: List<String> = Args.parse(arguments, args) ?: listOf()
    val sources = if (arguments.src.isNotEmpty()) arguments.src.split(File.pathSeparatorChar).toList() + freeArgs else freeArgs
    val samples = if (arguments.samples.isNotEmpty()) arguments.samples.split(File.pathSeparatorChar).toList() else listOf()
    val includes = if (arguments.include.isNotEmpty()) arguments.include.split(File.pathSeparatorChar).toList() else listOf()

    val sourceLinks = if (arguments.srcLink.isNotEmpty() && arguments.srcLink.contains("="))
        listOf(parseSourceLinkDefinition(arguments.srcLink))
    else {
        if (arguments.srcLink.isNotEmpty()) {
            println("Warning: Invalid -srcLink syntax. Expected: <path>=<url>[#lineSuffix]. No source links will be generated.")
        }
        listOf()
    }

    val environment = AnalysisEnvironment(MessageCollectorPlainTextToStream.PLAIN_TEXT_TO_SYSTEM_ERR) {
        addClasspath(PathUtil.getJdkClassesRoots())
        //   addClasspath(PathUtil.getKotlinPathsForCompiler().getRuntimePath())
        for (element in arguments.classpath.split(File.pathSeparatorChar)) {
            addClasspath(File(element))
        }

        addSources(sources)
        addSources(samples)
    }

    println("Module: ${arguments.moduleName}")
    println("Output: ${arguments.outputDir}")
    println("Sources: ${environment.sources.join()}")
    println("Classpath: ${environment.classpath.joinToString()}")

    println()

    println("Analysing sources and libraries... ")
    val startAnalyse = System.currentTimeMillis()


    val documentation = environment.withContext { environment, session ->
        val fragmentFiles = environment.getSourceFiles().filter {
            val sourceFile = File(it.getVirtualFile()!!.getPath())
            samples.none { sample ->
                val canonicalSample = File(sample).canonicalPath
                val canonicalSource = sourceFile.canonicalPath
                canonicalSource.startsWith(canonicalSample)
            }
        }
        val fragments = fragmentFiles.map { session.getPackageFragment(it.getPackageFqName()) }.filterNotNull().distinct()
        val options = DocumentationOptions(false, sourceLinks)
        val documentationBuilder = DocumentationBuilder(session, options)

        with(documentationBuilder) {

            val moduleContent = Content()
            for (include in includes) {
                val file = File(include)
                if (file.exists()) {
                    val text = file.readText()
                    val tree = parseMarkdown(text)
                    val content = buildContent(tree)
                    moduleContent.children.addAll(content.children)
                } else {
                    println("WARN: Include file $file was not found.")
                }
            }

            val documentationModule = DocumentationModule(arguments.moduleName, moduleContent)
            documentationModule.appendFragments(fragments)
            documentationBuilder.resolveReferences(documentationModule)
            documentationModule
        }
    }

    val timeAnalyse = System.currentTimeMillis() - startAnalyse
    println("done in ${timeAnalyse / 1000} secs")

    val startBuild = System.currentTimeMillis()
    val signatureGenerator = KotlinLanguageService()
    val locationService = FoldersLocationService(arguments.outputDir)
    val templateService = HtmlTemplateService.default("/dokka/styles/style.css")

    val (formatter, outlineFormatter) = when (arguments.outputFormat) {
        "html" -> {
            val htmlFormatService = HtmlFormatService(locationService, signatureGenerator, templateService)
            htmlFormatService to htmlFormatService
        }
        "markdown" -> MarkdownFormatService(locationService, signatureGenerator) to null
        "jekyll" -> JekyllFormatService(locationService, signatureGenerator) to null
        "kotlin-website" -> KotlinWebsiteFormatService(locationService, signatureGenerator) to
                    YamlOutlineService(locationService, signatureGenerator)
        else -> {
            print("Unrecognized output format ${arguments.outputFormat}")
            null to null
        }
    }
    if (formatter == null) return

    val generator = FileGenerator(signatureGenerator, locationService, formatter, outlineFormatter)
    print("Generating pages... ")
    generator.buildPage(documentation)
    generator.buildOutline(documentation)
    val timeBuild = System.currentTimeMillis() - startBuild
    println("done in ${timeBuild / 1000} secs")
    println()
    println("Done.")
    Disposer.dispose(environment)
}

