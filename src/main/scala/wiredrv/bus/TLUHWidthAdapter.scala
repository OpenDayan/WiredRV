package wiredrv.bus

import chisel3._
import chisel3.util._
import wiredrv.CoreParams

class TLUHWidthAdapter(inWidth: Int, outWidth: Int)(implicit p: CoreParams) extends Module {
  require(inWidth > 0 && outWidth > 0)
  require(isPow2(inWidth / 8), "TL-UH beat bytes must be power-of-two")
  require(isPow2(outWidth / 8), "TL-UH beat bytes must be power-of-two")
  require(inWidth <= outWidth, "current width adapter only supports up-conversion")

  val io = IO(new Bundle {
    val in = Flipped(new TLBundleUH(inWidth))
    val out = new TLBundleUH(outWidth)
  })

  if (inWidth == outWidth) {
    io.out <> io.in
  } else {
    val inBytes = inWidth / 8
    val outBytes = outWidth / 8
    val inBeatLg = log2Ceil(inBytes)
    val outBeatLg = log2Ceil(outBytes)
    val ratio = outBytes / inBytes
    val laneBits = math.max(1, log2Ceil(ratio))
    val countBits = 16

    def pow2Bytes(size: UInt): UInt = {
      val bytes = Wire(UInt(countBits.W))
      bytes := (1.U(countBits.W) << size)(countBits - 1, 0)
      bytes
    }

    def beatCount(size: UInt, beatLg: Int): UInt = {
      val cnt = Wire(UInt(countBits.W))
      cnt := Mux(size <= beatLg.U, 1.U(countBits.W), pow2Bytes(size) >> beatLg)
      cnt
    }

    def reqSizeOnOut(size: UInt): UInt = Mux(size <= outBeatLg.U, size, outBeatLg.U)

    val isReadIn = io.in.a.bits.opcode === TLOpcode.Get
    val inWriteReq = io.in.a.valid && !isReadIn

    val writeActive = RegInit(false.B)
    val writeReqReg = RegInit(0.U.asTypeOf(new TLUHChannelA(inWidth)))
    val writeDataReg = RegInit(0.U(outWidth.W))
    val writeMaskReg = RegInit(0.U(outBytes.W))
    val writeBurstBeatsLeft = RegInit(0.U(countBits.W))
    val writeGroupBeatsTotal = RegInit(0.U(countBits.W))
    val writeGroupBeatsSeen = RegInit(0.U(countBits.W))
    val writeSending = RegInit(false.B)

    val writeLaneData = writeDataReg.asTypeOf(Vec(ratio, UInt(inWidth.W)))
    val writeLaneMask = writeMaskReg.asTypeOf(Vec(ratio, UInt(inBytes.W)))

    val curWriteReq = Mux(writeActive, writeReqReg, io.in.a.bits)
    val curWriteOutSize = reqSizeOnOut(curWriteReq.size)
    val curWriteBurstBeats = beatCount(curWriteReq.size, inBeatLg)
    val curWriteGroupBeats = beatCount(curWriteOutSize, inBeatLg)

    val readReq = Wire(new TLUHChannelA(outWidth))
    readReq := 0.U.asTypeOf(readReq)
    readReq.opcode := io.in.a.bits.opcode
    readReq.param := io.in.a.bits.param
    readReq.size := io.in.a.bits.size
    readReq.source := io.in.a.bits.source
    readReq.address := io.in.a.bits.address
    readReq.mask := Fill(outBytes, true.B)
    readReq.data := 0.U
    readReq.corrupt := io.in.a.bits.corrupt

    val writeReqOut = Wire(new TLUHChannelA(outWidth))
    writeReqOut := 0.U.asTypeOf(writeReqOut)
    writeReqOut.opcode := Mux(writeMaskReg.andR, TLOpcode.PutFullData, TLOpcode.PutPartialData)
    writeReqOut.param := writeReqReg.param
    writeReqOut.size := curWriteOutSize
    writeReqOut.source := writeReqReg.source
    writeReqOut.address := writeReqReg.address
    writeReqOut.mask := writeMaskReg
    writeReqOut.data := writeDataReg
    writeReqOut.corrupt := writeReqReg.corrupt

    val outSendingWrite = writeSending
    io.out.a.valid := Mux(outSendingWrite, true.B, io.in.a.valid && !writeActive)
    io.out.a.bits := Mux(outSendingWrite, writeReqOut, readReq)

    io.in.a.ready := Mux(
      writeActive,
      !writeSending,
      Mux(isReadIn, io.out.a.ready, !writeActive)
    )

    when(inWriteReq && io.in.a.ready) {
      val nextLaneData = Wire(Vec(ratio, UInt(inWidth.W)))
      val nextLaneMask = Wire(Vec(ratio, UInt(inBytes.W)))
      for (i <- 0 until ratio) {
        nextLaneData(i) := Mux(writeActive, writeLaneData(i), 0.U)
        nextLaneMask(i) := Mux(writeActive, writeLaneMask(i), 0.U)
      }

      val lane = if (ratio == 1) {
        0.U(laneBits.W)
      } else {
        io.in.a.bits.address(outBeatLg - 1, inBeatLg)
      }

      nextLaneData(lane) := io.in.a.bits.data
      nextLaneMask(lane) := io.in.a.bits.mask
      writeDataReg := nextLaneData.asUInt
      writeMaskReg := nextLaneMask.asUInt

      when(!writeActive) {
        writeActive := true.B
        writeReqReg := io.in.a.bits
        writeBurstBeatsLeft := curWriteBurstBeats - 1.U
        writeGroupBeatsTotal := curWriteGroupBeats
        writeGroupBeatsSeen := 1.U
        writeSending := curWriteGroupBeats === 1.U
      }.otherwise {
        writeBurstBeatsLeft := writeBurstBeatsLeft - 1.U
        writeGroupBeatsSeen := writeGroupBeatsSeen + 1.U
        writeSending := (writeGroupBeatsSeen + 1.U) === writeGroupBeatsTotal
      }
    }

    when(outSendingWrite && io.out.a.fire) {
      when(writeBurstBeatsLeft === 0.U) {
        writeActive := false.B
        writeSending := false.B
        writeGroupBeatsSeen := 0.U
        writeGroupBeatsTotal := 0.U
        writeDataReg := 0.U
        writeMaskReg := 0.U
      }.otherwise {
        writeSending := false.B
        writeGroupBeatsSeen := 0.U
        writeDataReg := 0.U
        writeMaskReg := 0.U
      }
    }

    val pendingIsRead = RegInit(false.B)
    val pendingReadBaseLane = RegInit(0.U(laneBits.W))

    when(io.out.a.fire) {
      pendingIsRead := !outSendingWrite
      if (ratio == 1) {
        pendingReadBaseLane := 0.U
      } else {
        pendingReadBaseLane := Mux(
          io.out.a.bits.size <= outBeatLg.U,
          io.out.a.bits.address(outBeatLg - 1, inBeatLg),
          0.U
        )
      }
    }

    val readRespActive = RegInit(false.B)
    val readRespReg = RegInit(0.U.asTypeOf(new TLUHChannelD(outWidth)))
    val readRespBaseLane = RegInit(0.U(laneBits.W))
    val readRespSliceIdx = RegInit(0.U((laneBits + 1).W))
    val readRespSliceCount = RegInit(0.U((laneBits + 1).W))

    val readRespLanes = readRespReg.data.asTypeOf(Vec(ratio, UInt(inWidth.W)))
    val readRespLane = (readRespBaseLane + readRespSliceIdx)(laneBits - 1, 0)
    val readRespLast = readRespSliceIdx === (readRespSliceCount - 1.U)

    io.out.d.ready := Mux(pendingIsRead, !readRespActive, io.in.d.ready)

    when(!readRespActive && pendingIsRead && io.out.d.fire) {
      val nextSliceCount = Wire(UInt((laneBits + 1).W))
      nextSliceCount := Mux(
        io.out.d.bits.size <= inBeatLg.U,
        1.U,
        (pow2Bytes(io.out.d.bits.size) >> inBeatLg)
      )
      readRespReg := io.out.d.bits
      readRespBaseLane := pendingReadBaseLane
      readRespSliceIdx := 0.U
      readRespSliceCount := nextSliceCount
      readRespActive := true.B
    }

    io.in.d.valid := Mux(readRespActive, true.B, !pendingIsRead && io.out.d.valid)
    io.in.d.bits := 0.U.asTypeOf(io.in.d.bits)
    io.in.d.bits.opcode := Mux(readRespActive, readRespReg.opcode, io.out.d.bits.opcode)
    io.in.d.bits.param := Mux(readRespActive, readRespReg.param, io.out.d.bits.param)
    io.in.d.bits.size := Mux(
      readRespActive,
      Mux(readRespSliceCount === 1.U, readRespReg.size, inBeatLg.U),
      io.out.d.bits.size
    )
    io.in.d.bits.source := Mux(readRespActive, readRespReg.source, io.out.d.bits.source)
    io.in.d.bits.sink := Mux(readRespActive, readRespReg.sink, io.out.d.bits.sink)
    io.in.d.bits.denied := Mux(readRespActive, readRespReg.denied, io.out.d.bits.denied)
    io.in.d.bits.data := Mux(readRespActive, readRespLanes(readRespLane), io.out.d.bits.data(inWidth - 1, 0))
    io.in.d.bits.corrupt := Mux(readRespActive, readRespReg.corrupt, io.out.d.bits.corrupt)

    when(readRespActive && io.in.d.fire) {
      when(readRespLast) {
        readRespActive := false.B
      }.otherwise {
        readRespSliceIdx := readRespSliceIdx + 1.U
      }
    }
  }
}
