/*
 * Copyright (c) 2026 Institute of Computing Technology, Chinese Academy of Sciences
 * SPDX-License-Identifier: MulanPSL-2.0
 *
 * WiredRV is licensed under the Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *
 *     http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 */

package wiredrv

import _root_.circt.stage.ChiselStage
import chisel3._
import difftest._
import wiredrv.harness.{SimTop, WiredCoreHarnessTop}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

object Main {
  private val DefaultTop = "wiredrv.harness.WiredCoreHarnessTop"

  final case class GenOptions(
    top: String = DefaultTop,
    targetDir: String = "",
    replSeqMem: Boolean = false,
    simUncachedInRam: Boolean = false
  )

  def main(args: Array[String]): Unit = {
    val options = parseArgs(args.toList)
    val topName = options.top.split('.').lastOption.getOrElse(options.top)
    val targetDir =
      if (options.targetDir.nonEmpty) options.targetDir else s"generated/$topName"

    implicit val p: CoreParams = coreParams(options.simUncachedInRam)
    val firtoolOpts = Seq(
      "--mlir-disable-threading",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      s"-o=$targetDir"
    ) ++ Option.when(options.replSeqMem)("--repl-seq-mem")

    ChiselStage.emitSystemVerilog(instantiateTop(options.top), firtoolOpts = firtoolOpts.toArray)
    writeFileList(Paths.get(targetDir))
  }

  private def coreParams(simUncachedInRam: Boolean): CoreParams = {
    val base = CoreParams()
    if (simUncachedInRam) {
      base.copy(uncachedRegions = Seq(AddrRegion(0x80001000L, 0x1000L)))
    } else {
      base
    }
  }

  private def instantiateTop(top: String)(implicit p: CoreParams): RawModule = top match {
    case DefaultTop | "WiredCoreHarnessTop" | "harness.WiredCoreHarnessTop" =>
      new SimTop()
    case other =>
      val className = if (other.contains(".")) other else s"wiredrv.$other"
      Class
        .forName(className)
        .getConstructor()
        .newInstance() match {
        case module: RawModule => module
        case module =>
          throw new IllegalArgumentException(
            s"$className must be a chisel3.RawModule, got ${module.getClass.getName}"
          )
      }
  }

  private def parseArgs(args: List[String]): GenOptions = {
    def parseBool(name: String, value: String): Boolean = value.toLowerCase match {
      case "1" | "true" | "yes" => true
      case "0" | "false" | "no" => false
      case other => throw new IllegalArgumentException(s"$name expects a boolean value, got $other")
    }

    @annotation.tailrec
    def loop(rest: List[String], options: GenOptions): GenOptions = rest match {
      case Nil => options
      case "--top" :: value :: tail => loop(tail, options.copy(top = value))
      case "--target-dir" :: value :: tail => loop(tail, options.copy(targetDir = value))
      case "--repl-seq-mem" :: value :: tail =>
        loop(tail, options.copy(replSeqMem = parseBool("--repl-seq-mem", value)))
      case "--sim-uncached-in-ram" :: value :: tail =>
        loop(tail, options.copy(simUncachedInRam = parseBool("--sim-uncached-in-ram", value)))
      case flag :: _ => throw new IllegalArgumentException(s"unknown argument: $flag")
    }

    loop(args, GenOptions())
  }

  private def writeFileList(targetDir: Path): Unit = {
    if (!Files.isDirectory(targetDir)) {
      throw new IllegalStateException(s"generated RTL directory not found: $targetDir")
    }

    val rtlFiles = Files
      .walk(targetDir)
      .iterator()
      .asScala
      .filter(path => Files.isRegularFile(path))
      .filter { path =>
        val name = path.getFileName.toString
        name.endsWith(".sv") || name.endsWith(".v")
      }
      .toSeq
      .sortBy(_.toString)
      .map(_.toString)

    Files.write(
      targetDir.resolve("filelist.f"),
      (rtlFiles.mkString(System.lineSeparator()) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8)
    )
  }
}
