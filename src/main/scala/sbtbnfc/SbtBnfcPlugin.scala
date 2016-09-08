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

object SbtBnfcPlugin extends AutoPlugin {

  object autoImport {

    val bnfcCommand = settingKey[String]("The bnfc command")
    val bnfcSuffix = settingKey[String]("Suffix for bnfc gammar files")
    val bnfcSrcDirectory = settingKey[File]("Directory for bnfc sources")
    val bnfcTgtDirectory = settingKey[File]("Bnfc target directory")
    val bnfcSources = taskKey[Seq[File]]("The list of sources to process")
    val bnfcBasePackage = taskKey[Option[String]]("Package prepended to generated files")

    val bisonCommand = settingKey[String]("The bison command")
    val scalaBisonJar = settingKey[File]("The scala-bison.jar file")

    val genGrammars = taskKey[Seq[File]]("Generate parser/lexer from bnfc grammar")

  }

  import autoImport._

  override def trigger = allRequirements

  override lazy val projectSettings = Seq(

    bnfcCommand := "bnfc",
    bnfcSuffix := ".cf",
    bnfcSrcDirectory := (sourceDirectory in Compile).value / "bnfc",
    bnfcTgtDirectory := (sourceManaged in Compile).value,
    bnfcBasePackage := None,

    bnfcSources := {
      val srcDir = bnfcSrcDirectory.value
      val suffix = bnfcSuffix.value
      (srcDir ** ("*" + suffix)).get
    },

    scalaBisonJar := unmanagedBase.value / "scala-bison-2.11.jar",

    bisonCommand := "bison",

    genGrammars := {

      val log = streams.value.log
      val sources = bnfcSources.value
      val cmd = bnfcCommand.value
      val wd = bnfcSrcDirectory.value
      val td = bnfcTgtDirectory.value
      val sbjar = scalaBisonJar.value
      val sh = scalaHome.value

      val (pkgOpt, outDir) : (List[String], File) =
        bnfcBasePackage.value match {
          case None => (List(), td)
          case Some(pkgName) => (List("-p", pkgName), td / pkgName.replace(".", "/"))
        }

      for {
        file <- sources
      } {

        log.info("Generating source from: " + file.name)

        Process(List(cmd, "--scala", "-o", td.absolutePath) ++ pkgOpt ++ List(file.name), wd) ! log

        log.info("Running bison ...")
        Process("bison" :: "-v" :: "Parser.y" :: Nil, outDir) ! log

        log.info("Running scala-bison on generated parser ...")

        val forkOpts = ForkOptions(
          bootJars = sbjar +: scalaInstance.value.allJars().toSeq,
          workingDirectory = Some(outDir)
        )

        val forkArgs = Seq("-howtorun:object", "edu.uwm.cs.scalabison.RunGenerator", "-v", "Parser.y")

        Fork.scala(forkOpts, forkArgs)

        log.info("Running jflex-scala to generate the lexer ...")

        Options.emitScala = true
        Options.setDir(outDir.getPath)
        Main.generate(outDir / "Lexer.flex")

      }

      Seq(
        outDir / "Syntax.scala",
        outDir / "Lexer.scala",
        outDir / "ParserParser.scala",
        outDir / "ParserTokens.scala"
      )
    },

    sourceGenerators in Compile += genGrammars.taskValue

  )

}
