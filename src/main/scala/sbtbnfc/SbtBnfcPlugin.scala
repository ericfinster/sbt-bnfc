/*
 * Copyright 2016 Eric Finster
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sbtbnfc

import sbt._
import Keys._
import jflex.Options
import jflex.Main
import plugins._

object SbtBnfcPlugin extends AutoPlugin {

  object autoImport {

    val bnfcCommand = settingKey[String]("The bnfc command")
    val bnfcSrcDirectory = settingKey[File]("Directory for bnfc sources")
    val bnfcTgtDirectory = settingKey[File]("Bnfc target directory")
    val bnfcBasePackage = taskKey[Option[String]]("Package prepended to generated files")

    val bisonCommand = settingKey[String]("The bison command")
    val scalaBisonJar = settingKey[File]("The scala-bison.jar file")
    val scalaBisonDebug = settingKey[Boolean]("Generate debug information in parser")

    val genGrammars = taskKey[Seq[File]]("Generate parser/lexer from bnfc grammar")

  }

  import autoImport._

  override def requires = JvmPlugin
  override def trigger = allRequirements

  override lazy val projectSettings = Seq(

    bnfcCommand := "bnfc",
    bnfcSrcDirectory := (sourceDirectory in Compile).value / "bnfc",
    bnfcTgtDirectory := (sourceManaged in Compile).value,
    bnfcBasePackage := None,

    bisonCommand := "bison",
    scalaBisonDebug := false,
    scalaBisonJar := baseDirectory.value / "project" / "lib" / "scala-bison-2.11.jar",

    genGrammars := {

      val srcDir = bnfcSrcDirectory.value
      val tgtDir = bnfcTgtDirectory.value
      val bnfcCmd = bnfcCommand.value
      val sbJar = scalaBisonJar.value
      val basePkg = bnfcBasePackage.value
      val scalaJars = scalaInstance.value.allJars().toSeq
      val debug = scalaBisonDebug.value
      val log = streams.value.log

      val cc = FileFunction.cached(
        streams.value.cacheDirectory / "bnfc",
        inStyle = FilesInfo.lastModified,
        outStyle = FilesInfo.exists
      ) { (in : Set[File]) =>
        BnfcGenerator.generateGrammars(
          srcDir, tgtDir, bnfcCmd, sbJar,
          basePkg, scalaJars, debug, log
        ).toSet
      }

      cc((srcDir ** "*.cf").get.toSet).toSeq

    },

    sourceGenerators in Compile += genGrammars.taskValue

  )

  object BnfcGenerator {

    def generateGrammars(
      srcDir: File, tgtDir: File,
      bnfcCmd: String, sbJar: File,
      basePkg: Option[String],
      scalaJars: Seq[File],
      debug: Boolean, log: Logger
    ) : Seq[File] = {

      val sources = (srcDir ** ("*.cf")).get

      val fileLists =
        for {
          file <- sources
        } yield {

          log.info("Generating source from: " + file.name)

          val langName = file.base
          val bisonFname = langName + ".y"
          val jflexFname = langName + ".flex"

          val pkgName = (basePkg getOrElse "") + "." + langName.toLowerCase
          val pkgOpt = if (basePkg.isDefined) List("-p", basePkg.get) else List()

          val outDir = tgtDir / pkgName.replace(".", "/")

          Process(List(bnfcCmd, "--scala", "-o", tgtDir.absolutePath) ++ pkgOpt ++ List(file.name), srcDir) ! log

          log.info("Running bison ...")
          Process("bison" :: "-v" :: bisonFname :: Nil, outDir) ! log

          log.info("Running scala-bison on generated parser ...")

          val forkOpts = ForkOptions(
            bootJars = sbJar +: scalaJars,
            workingDirectory = Some(outDir)
          )

          val forkArgs =
            Seq("-howtorun:object", "edu.uwm.cs.scalabison.RunGenerator", "-v") ++
              (if (debug) Seq("-t") else Seq()) ++
              Seq(bisonFname)

          Fork.scala(forkOpts, forkArgs)

          val pbaseFile = outDir / (langName + "ParserBase.scala")
          val pbaseImpl =
            "package " + pkgName + "\n\n" +
          "class " + langName + "ParserBase { }"

          IO.write(pbaseFile, pbaseImpl)

          log.info("Running jflex-scala to generate the lexer ...")

          Options.emitScala = true
          Options.setDir(outDir.getPath)
          Main.generate(outDir / jflexFname)

          Seq(
            outDir / (langName + "Syntax.scala"),
            outDir / (langName + "Lexer.scala"),
            outDir / (langName + "Parser.scala"),
            outDir / (langName + "Tokens.scala"),
            pbaseFile
          )

        }

      fileLists.flatten

    }

  }

}
