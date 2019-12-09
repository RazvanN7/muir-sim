/**
 * Author: Amirali Sharifian
 */

package dandelion.shell

import chisel3._
import chisel3.util._
import dandelion.config._
import dandelion.generator._
import dandelion.interfaces.{ControlBundle, DataBundle}
import dandelion.interfaces.axi._
import dandelion.memory.cache._

/** Register File.
 *
 * Six 32-bit register file.
 *
 * -------------------------------
 * Register description    | addr
 * -------------------------|-----
 * Control status register | 0x00
 * Cycle counter           | 0x04
 * Constant value          | 0x08
 * Vector length           | 0x0c
 * Input pointer lsb       | 0x10
 * Input pointer msb       | 0x14
 * Output pointer lsb      | 0x18
 * Output pointer msb      | 0x1c
 * -------------------------------
 *
 * ------------------------------
 * Control status register | bit
 * ------------------------------
 * Launch                  | 0
 * Finish                  | 1
 * ------------------------------
 */


/*
+------------------+                          +-----------------+
|                  | f(bits)+--------+        |                 |
|   VMEReadMaster  +------->+Buffers +-------->VMEWriteMaster   |
|                  |        +--------+        |                 |
+------------------+                          +-----------------+

 */

/* Receives a counter value as input. Waits for N cycles and then returns N + const as output */

/**
 *
 * @param p
 * @todo define your own ShellKey
 */
class DandelionVTAShell(implicit p: Parameters) extends MultiIOModule {
  val io = IO(new Bundle {
    val host = new AXILiteClient(p(ShellKey).hostParams)
    val mem = new AXIMaster(p(ShellKey).memParams)
  })

  val vcr = Module(new VCR)
  val vmem = Module(new VME)

  val buffer = Module(new Queue(vmem.io.vme.rd(0).data.bits.cloneType, 40))

  val sIdle :: sReq :: sBusy :: Nil = Enum(3)
  val Rstate = RegInit(sIdle)
  val Wstate = RegInit(sIdle)

  val cycle_count = new Counter(200)

  when(Rstate =/= sIdle) {
    cycle_count.inc()
  }


  vcr.io.vcr.ecnt(0.U).bits := cycle_count.value

  // Read state machine
  switch(Rstate) {
    is(sIdle) {
      when(vcr.io.vcr.launch) {
        cycle_count.value := 0.U
        Rstate := sReq
      }
    }
    is(sReq) {
      when(vmem.io.vme.rd(0).cmd.fire()) {
        Rstate := sBusy
      }
    }
  }
  // Write state machine
  switch(Wstate) {
    is(sIdle) {
      when(vcr.io.vcr.launch) {
        Wstate := sReq
      }
    }
    is(sReq) {
      when(vmem.io.vme.wr(0).cmd.fire()) {
        Wstate := sBusy
      }
    }
  }

  vmem.io.vme.rd(0).cmd.bits.addr := vcr.io.vcr.ptrs(0)
  vmem.io.vme.rd(0).cmd.bits.len := vcr.io.vcr.vals(1)
  vmem.io.vme.rd(0).cmd.valid := false.B

  vmem.io.vme.wr(0).cmd.bits.addr := vcr.io.vcr.ptrs(2)
  vmem.io.vme.wr(0).cmd.bits.len := vcr.io.vcr.vals(1)
  vmem.io.vme.wr(0).cmd.valid := false.B

  when(Rstate === sReq) {
    vmem.io.vme.rd(0).cmd.valid := true.B
  }

  when(Wstate === sReq) {
    vmem.io.vme.wr(0).cmd.valid := true.B
  }

  // Final
  val last = Wstate === sBusy && vmem.io.vme.wr(0).ack
  vcr.io.vcr.finish := last
  vcr.io.vcr.ecnt(0).valid := last

  when(vmem.io.vme.wr(0).ack) {
    Rstate := sIdle
    Wstate := sIdle
  }


  buffer.io.enq <> vmem.io.vme.rd(0).data
  buffer.io.enq.bits := vmem.io.vme.rd(0).data.bits + vcr.io.vcr.vals(0)
  vmem.io.vme.wr(0).data <> buffer.io.deq

  io.mem <> vmem.io.mem
  io.host <> vcr.io.host

}

/* Receives a counter value as input. Waits for N cycles and then returns N + const as output */
class DandelionCacheShell(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val host = new AXILiteClient(p(ShellKey).hostParams)
    val mem = new AXIMaster(p(ShellKey).memParams)
  })

  val regBits = p(ShellKey).vcrParams.regBits
  //val ptrBits = regBits * 2
  val ptrBits = regBits

  val vcr = Module(new VCR)
  val cache = Module(new SimpleCache())

  val test09 = Module(new test14DF())

  cache.io.cpu.req <> test09.io.MemReq
  test09.io.MemResp <> cache.io.cpu.resp

  val sIdle :: sBusy :: sFlush :: sDone :: Nil = Enum(4)

  val state = RegInit(sIdle)
  val cycles = RegInit(0.U(regBits.W))
  val cnt = RegInit(0.U(regBits.W))
  val last = state === sDone
  val is_busy = state === sBusy

  when(state === sIdle) {
    cycles := 0.U
  }.otherwise {
    cycles := cycles + 1.U
  }

  vcr.io.vcr.ecnt(0).valid := last
  vcr.io.vcr.ecnt(0).bits := cycles


  /**
   * @note This part needs to be changes for each function
   */
  val ptr_a = RegEnable(next = vcr.io.vcr.ptrs(0), init = 0.U(ptrBits.W), enable = (state === sIdle))
  val ptr_b = RegEnable(next = vcr.io.vcr.ptrs(1), init = 0.U(ptrBits.W), enable = (state === sIdle))
  //val ptr_c = RegEnable(next = vcr.io.vcr.ptrs(2), init = 0.U(ptrBits.W), enable = (state === sIdle))

  val val_a = vcr.io.vcr.vals(0)
  //val val_b = vcr.io.vcr.vals(1)

  test09.io.in.bits.data("field0") := DataBundle(ptr_a)
  test09.io.in.bits.data("field1") := DataBundle(ptr_b)

  test09.io.in.bits.data("field2") := DataBundle(val_a)
  //test09.io.in.bits.data("field2") := DataBundle(ptr_c)

  test09.io.in.bits.enable := ControlBundle.active()


  test09.io.in.valid := false.B
  test09.io.out.ready := is_busy

  cache.io.cpu.abort := false.B
  cache.io.cpu.flush := false.B

  switch(state) {
    is(sIdle) {
      when(vcr.io.vcr.launch) {
        printf(p" Ptrs: ptr(0): ${ptr_a}, ptr(1): ${ptr_b}, val(0): ${val_a}\n")
        test09.io.in.valid := true.B
        when(test09.io.in.fire){
          state := sBusy
        }
      }
    }
    is(sBusy) {
      when(test09.io.out.fire){
        state := sDone
      }
    }
    is(sFlush){
      cache.io.cpu.flush := true.B
      when(cache.io.cpu.flush_done){
        state := sDone
      }
    }
    is(sDone){
      state := sIdle
    }
  }


  vcr.io.vcr.finish := last

  io.mem <> cache.io.mem
  vcr.io.host <> io.host

}



/* Receives a counter value as input. Waits for N cycles and then returns N + const as output */
class DandelionTest09CacheShell(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val host = new AXILiteClient(p(ShellKey).hostParams)
    val mem = new AXIMaster(p(ShellKey).memParams)
  })

  val regBits = p(ShellKey).vcrParams.regBits
  val ptrBits = regBits * 2

  val vcr = Module(new VCR)
  val cache = Module(new SimpleCache())

  val test09 = Module(new test09DF())

  cache.io.cpu.req <> test09.io.MemReq
  test09.io.MemResp <> cache.io.cpu.resp

  val sIdle :: sBusy :: sFlush :: sDone :: Nil = Enum(4)

  val state = RegInit(sIdle)
  val cycles = RegInit(0.U(regBits.W))
  val cnt = RegInit(0.U(regBits.W))
  val last = state === sDone
  val is_busy = state === sBusy

  when(state === sIdle) {
    cycles := 0.U
  }.otherwise {
    cycles := cycles + 1.U
  }

  vcr.io.vcr.ecnt(0).valid := last
  vcr.io.vcr.ecnt(0).bits := cycles


  /**
   * @note This part needs to be changes for each function
   */
  val ptr_a = RegEnable(next = vcr.io.vcr.ptrs(0), init = 0.U(ptrBits.W), enable = (state === sIdle))
  val ptr_b = RegEnable(next = vcr.io.vcr.ptrs(1), init = 0.U(ptrBits.W), enable = (state === sIdle))
  val ptr_c = RegEnable(next = vcr.io.vcr.ptrs(2), init = 0.U(ptrBits.W), enable = (state === sIdle))

  val constValue  = vcr.io.vcr.vals(0)
  val lengthValue = vcr.io.vcr.vals(1)

  test09.io.in.bits.data("field0") := DataBundle(ptr_a)
  test09.io.in.bits.data("field1") := DataBundle(ptr_b)
  test09.io.in.bits.data("field2") := DataBundle(ptr_c)

  test09.io.in.bits.enable := ControlBundle.active()


  test09.io.in.valid := false.B
  test09.io.out.ready := is_busy

  cache.io.cpu.abort := false.B
  cache.io.cpu.flush := false.B

  switch(state) {
    is(sIdle) {
      when(vcr.io.vcr.launch) {
        printf(p"Input: a = ${ptr_a}, b = ${ptr_b}, c = ${ptr_c}\n")
        printf(p" Vals: val(0): ${constValue}, val(1): ${lengthValue}\n")
        test09.io.in.valid := true.B
        when(test09.io.in.fire){
          state := sBusy
        }
      }
    }
    is(sBusy) {
      when(test09.io.out.fire){
        state := sFlush
      }
    }
    is(sFlush){
      cache.io.cpu.flush := true.B
      when(cache.io.cpu.flush_done){
        state := sDone
      }
    }
    is(sDone){
      state := sIdle
    }
  }


  vcr.io.vcr.finish := last

  io.mem <> cache.io.mem
  vcr.io.host <> io.host

}

/* Receives a counter value as input. Waits for N cycles and then returns N + const as output */
class DandelionCacheShellConv3x3Halide(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val host = new AXILiteClient(p(ShellKey).hostParams)
    val mem = new AXIMaster(p(ShellKey).memParams)
  })

  val regBits = p(ShellKey).vcrParams.regBits
  val ptrBits = regBits

  val vcr = Module(new VCR)
  val cache = Module(new SimpleCache())

  val accel = Module(new conv3x3HalideDF())

  cache.io.cpu.req <> accel.io.MemReq
  accel.io.MemResp <> cache.io.cpu.resp

  val sIdle :: sBusy :: sFlush :: sDone :: Nil = Enum(4)

  val state = RegInit(sIdle)
  val cycles = RegInit(0.U(regBits.W))
  val cnt = RegInit(0.U(regBits.W))
  val last = state === sDone
  val is_busy = state === sBusy

  when(state === sIdle) {
    cycles := 0.U
  }.otherwise {
    cycles := cycles + 1.U
  }

  vcr.io.vcr.ecnt(0).valid := last
  vcr.io.vcr.ecnt(0).bits := cycles


  /**
   * @note This part needs to be changes for each function
   */
  val ptr_a = RegEnable(next = vcr.io.vcr.ptrs(0), init = 0.U(ptrBits.W), enable = (state === sIdle))
  val ptr_b = RegEnable(next = vcr.io.vcr.ptrs(1), init = 0.U(ptrBits.W), enable = (state === sIdle))
  val ptr_c = RegEnable(next = vcr.io.vcr.ptrs(2), init = 0.U(ptrBits.W), enable = (state === sIdle))

  val _13 = vcr.io.vcr.vals(0)
  val _16 = vcr.io.vcr.vals(1)
  val _18 = vcr.io.vcr.vals(2)

  accel.io.in.bits.data("field0") := DataBundle(ptr_a)
  accel.io.in.bits.data("field1") := DataBundle(ptr_b)
  accel.io.in.bits.data("field2") := DataBundle(ptr_c)
  accel.io.in.bits.data("field3") := DataBundle(_13)
  accel.io.in.bits.data("field4") := DataBundle(_16)
  accel.io.in.bits.data("field5") := DataBundle(_18)

  accel.io.in.bits.enable := ControlBundle.active()


  accel.io.in.valid := false.B
  accel.io.out.ready := is_busy

  cache.io.cpu.abort := false.B
  cache.io.cpu.flush := false.B

  switch(state) {
    is(sIdle) {
      when(vcr.io.vcr.launch) {
        printf(p"Input: a = ${ptr_a}, b = ${ptr_b}, c = ${ptr_c}\n")
        printf(p" Vals: val(0): ${_13}, val(1): ${_16}, val(2): ${_18}\n")
        accel.io.in.valid := true.B
        when(accel.io.in.fire){
          state := sBusy
        }
      }
    }
    is(sBusy) {
      when(accel.io.out.fire){
        state := sFlush
      }
    }
    is(sFlush){
      cache.io.cpu.flush := true.B
      when(cache.io.cpu.flush_done){
        state := sDone
      }
    }
    is(sDone){
      state := sIdle
    }
  }


  vcr.io.vcr.finish := last

  io.mem <> cache.io.mem
  vcr.io.host <> io.host

}


/* Receives a counter value as input. Waits for N cycles and then returns N + const as output */
class DandelionCacheShellStridedConvHalide(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val host = new AXILiteClient(p(ShellKey).hostParams)
    val mem = new AXIMaster(p(ShellKey).memParams)
  })

  val regBits = p(ShellKey).vcrParams.regBits
  val ptrBits = regBits

  val vcr = Module(new VCR)
  val cache = Module(new SimpleCache())

  val accel = Module(new stridedConvHalideDF())

  cache.io.cpu.req <> accel.io.MemReq
  accel.io.MemResp <> cache.io.cpu.resp

  val sIdle :: sBusy :: sFlush :: sDone :: Nil = Enum(4)

  val state = RegInit(sIdle)
  val cycles = RegInit(0.U(regBits.W))
  val cnt = RegInit(0.U(regBits.W))
  val last = state === sDone
  val is_busy = state === sBusy

  when(state === sIdle) {
    cycles := 0.U
  }.otherwise {
    cycles := cycles + 1.U
  }

  vcr.io.vcr.ecnt(0).valid := last
  vcr.io.vcr.ecnt(0).bits := cycles


  /**
   * @note This part needs to be changes for each function
   */
  val ptr_a = RegEnable(next = vcr.io.vcr.ptrs(0), init = 0.U(ptrBits.W), enable = (state === sIdle))
  val ptr_b = RegEnable(next = vcr.io.vcr.ptrs(1), init = 0.U(ptrBits.W), enable = (state === sIdle))
  val ptr_c = RegEnable(next = vcr.io.vcr.ptrs(2), init = 0.U(ptrBits.W), enable = (state === sIdle))

  val _13 = vcr.io.vcr.vals(0)
  val _16 = vcr.io.vcr.vals(1)
  val _18 = vcr.io.vcr.vals(2)

  accel.io.in.bits.data("field0") := DataBundle(ptr_a)
  accel.io.in.bits.data("field1") := DataBundle(ptr_b)
  accel.io.in.bits.data("field2") := DataBundle(ptr_c)
  accel.io.in.bits.data("field3") := DataBundle(_13)
  accel.io.in.bits.data("field4") := DataBundle(_16)
  accel.io.in.bits.data("field5") := DataBundle(_18)

  accel.io.in.bits.enable := ControlBundle.active()


  accel.io.in.valid := false.B
  accel.io.out.ready := is_busy

  cache.io.cpu.abort := false.B
  cache.io.cpu.flush := false.B

  switch(state) {
    is(sIdle) {
      when(vcr.io.vcr.launch) {
        printf(p"Input: a = ${ptr_a}, b = ${ptr_b}, c = ${ptr_c}\n")
        printf(p" Vals: val(0): ${_13}, val(1): ${_16}, val(2): ${_18}\n")
        accel.io.in.valid := true.B
        when(accel.io.in.fire){
          state := sBusy
        }
      }
    }
    is(sBusy) {
      when(accel.io.out.fire){
        state := sFlush
      }
    }
    is(sFlush){
      cache.io.cpu.flush := true.B
      when(cache.io.cpu.flush_done){
        state := sDone
      }
    }
    is(sDone){
      state := sIdle
    }
  }


  vcr.io.vcr.finish := last

  io.mem <> cache.io.mem
  vcr.io.host <> io.host

}


/* Receives a counter value as input. Waits for N cycles and then returns N + const as output */
class DandelionCacheShellUpSampleHalide(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val host = new AXILiteClient(p(ShellKey).hostParams)
    val mem = new AXIMaster(p(ShellKey).memParams)
  })

  val regBits = p(ShellKey).vcrParams.regBits
  val ptrBits = regBits

  val vcr = Module(new VCR)
  val cache = Module(new SimpleCache())

  val accel = Module(new upSampleHalideDF())

  cache.io.cpu.req <> accel.io.MemReq
  accel.io.MemResp <> cache.io.cpu.resp

  val sIdle :: sBusy :: sFlush :: sDone :: Nil = Enum(4)

  val state = RegInit(sIdle)
  val cycles = RegInit(0.U(regBits.W))
  val cnt = RegInit(0.U(regBits.W))
  val last = state === sDone
  val is_busy = state === sBusy

  when(state === sIdle) {
    cycles := 0.U
  }.otherwise {
    cycles := cycles + 1.U
  }

  vcr.io.vcr.ecnt(0).valid := last
  vcr.io.vcr.ecnt(0).bits := cycles


  /**
   * @note This part needs to be changes for each function
   */
  val ptr_a = RegEnable(next = vcr.io.vcr.ptrs(0), init = 0.U(ptrBits.W), enable = (state === sIdle))
  val ptr_b = RegEnable(next = vcr.io.vcr.ptrs(1), init = 0.U(ptrBits.W), enable = (state === sIdle))

  val args_num = 13
  val arguments = for (i <- 0 until args_num) yield{
    val arg = vcr.io.vcr.vals(i)
    arg
  }

  accel.io.in.bits.data("field0") := DataBundle(ptr_a)
  accel.io.in.bits.data("field1") := DataBundle(ptr_b)

  for(i <- 0 until args_num) {
    val index = i + 2
    accel.io.in.bits.data(s"field${index}") := DataBundle(arguments(i))
  }

  accel.io.in.bits.enable := ControlBundle.active()


  accel.io.in.valid := false.B
  accel.io.out.ready := is_busy

  cache.io.cpu.abort := false.B
  cache.io.cpu.flush := false.B

  switch(state) {
    is(sIdle) {
      when(vcr.io.vcr.launch) {
        printf(p"Input: a = ${ptr_a}, b = ${ptr_b}, \n")
        for(i <- 0 until args_num){
          printf(p" val(${i}): ${arguments(i)}\n")
        }
        accel.io.in.valid := true.B
        when(accel.io.in.fire){
          state := sBusy
        }
      }
    }
    is(sBusy) {
      when(accel.io.out.fire){
        state := sFlush
      }
    }
    is(sFlush){
      cache.io.cpu.flush := true.B
      when(cache.io.cpu.flush_done){
        state := sDone
      }
    }
    is(sDone){
      state := sIdle
    }
  }


  vcr.io.vcr.finish := last

  io.mem <> cache.io.mem
  vcr.io.host <> io.host

}


/* Receives a counter value as input. Waits for N cycles and then returns N + const as output */
class DandelionCacheShellConvLayer(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val host = new AXILiteClient(p(ShellKey).hostParams)
    val mem = new AXIMaster(p(ShellKey).memParams)
  })

  val regBits = p(ShellKey).vcrParams.regBits
  val ptrBits = regBits

  val vcr = Module(new VCR)
  val cache = Module(new SimpleCache())

  val accel = Module(new convLayerHalideDF())

  cache.io.cpu.req <> accel.io.MemReq
  accel.io.MemResp <> cache.io.cpu.resp

  val sIdle :: sBusy :: sFlush :: sDone :: Nil = Enum(4)

  val state = RegInit(sIdle)
  val cycles = RegInit(0.U(regBits.W))
  val cnt = RegInit(0.U(regBits.W))
  val last = state === sDone
  val is_busy = state === sBusy

  when(state === sIdle) {
    cycles := 0.U
  }.otherwise {
    cycles := cycles + 1.U
  }

  vcr.io.vcr.ecnt(0).valid := last
  vcr.io.vcr.ecnt(0).bits := cycles


  /**
   * @note This part needs to be changes for each function
   */
  val ptr_a = RegEnable(next = vcr.io.vcr.ptrs(0), init = 0.U(ptrBits.W), enable = (state === sIdle))
  val ptr_b = RegEnable(next = vcr.io.vcr.ptrs(1), init = 0.U(ptrBits.W), enable = (state === sIdle))

  val args_num = 13
  val arguments = for (i <- 0 until args_num) yield{
    val arg = vcr.io.vcr.vals(i)
    arg
  }

  accel.io.in.bits.data("field0") := DataBundle(ptr_a)
  accel.io.in.bits.data("field1") := DataBundle(ptr_b)

  for(i <- 0 until args_num) {
    val index = i + 2
    accel.io.in.bits.data(s"field${index}") := DataBundle(arguments(i))
  }

  accel.io.in.bits.enable := ControlBundle.active()


  accel.io.in.valid := false.B
  accel.io.out.ready := is_busy

  cache.io.cpu.abort := false.B
  cache.io.cpu.flush := false.B

  switch(state) {
    is(sIdle) {
      when(vcr.io.vcr.launch) {
        printf(p"Input: a = ${ptr_a}, b = ${ptr_b}, \n")
        for(i <- 0 until args_num){
          printf(p" val(${i}): ${arguments(i)}\n")
        }
        accel.io.in.valid := true.B
        when(accel.io.in.fire){
          state := sBusy
        }
      }
    }
    is(sBusy) {
      when(accel.io.out.fire){
        state := sFlush
      }
    }
    is(sFlush){
      cache.io.cpu.flush := true.B
      when(cache.io.cpu.flush_done){
        state := sDone
      }
    }
    is(sDone){
      state := sIdle
    }
  }


  vcr.io.vcr.finish := last

  io.mem <> cache.io.mem
  vcr.io.host <> io.host

}


/* Receives a counter value as input. Waits for N cycles and then returns N + const as output */
class DandelionCacheShellDownSample(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val host = new AXILiteClient(p(ShellKey).hostParams)
    val mem = new AXIMaster(p(ShellKey).memParams)
  })

  val regBits = p(ShellKey).vcrParams.regBits
  val ptrBits = regBits

  val vcr = Module(new VCR)
  val cache = Module(new SimpleCache())

  val accel = Module(new downSampleHalideDF())

  cache.io.cpu.req <> accel.io.MemReq
  accel.io.MemResp <> cache.io.cpu.resp

  val sIdle :: sBusy :: sFlush :: sDone :: Nil = Enum(4)

  val state = RegInit(sIdle)
  val cycles = RegInit(0.U(regBits.W))
  val cnt = RegInit(0.U(regBits.W))
  val last = state === sDone
  val is_busy = state === sBusy

  when(state === sIdle) {
    cycles := 0.U
  }.otherwise {
    cycles := cycles + 1.U
  }

  vcr.io.vcr.ecnt(0).valid := last
  vcr.io.vcr.ecnt(0).bits := cycles


  /**
   * @note This part needs to be changes for each function
   */
  val ptr_a = RegEnable(next = vcr.io.vcr.ptrs(0), init = 0.U(ptrBits.W), enable = (state === sIdle))
  //val ptr_b = RegEnable(next = vcr.io.vcr.ptrs(1), init = 0.U(ptrBits.W), enable = (state === sIdle))

  val args_num = 7
  val arguments = for (i <- 0 until args_num) yield{
    val arg = vcr.io.vcr.vals(i)
    arg
  }

  accel.io.in.bits.data("field0") := DataBundle(ptr_a)
  //accel.io.in.bits.data("field1") := DataBundle(ptr_b)

  for(i <- 0 until args_num) {
    val index = i + 2
    accel.io.in.bits.data(s"field${index}") := DataBundle(arguments(i))
  }

  accel.io.in.bits.enable := ControlBundle.active()


  accel.io.in.valid := false.B
  accel.io.out.ready := is_busy

  cache.io.cpu.abort := false.B
  cache.io.cpu.flush := false.B

  switch(state) {
    is(sIdle) {
      when(vcr.io.vcr.launch) {
        printf(p"Input: a = ${ptr_a}\n")
        for(i <- 0 until args_num){
          printf(p" val(${i}): ${arguments(i)}\n")
        }
        accel.io.in.valid := true.B
        when(accel.io.in.fire){
          state := sBusy
        }
      }
    }
    is(sBusy) {
      when(accel.io.out.fire){
        state := sFlush
      }
    }
    is(sFlush){
      cache.io.cpu.flush := true.B
      when(cache.io.cpu.flush_done){
        state := sDone
      }
    }
    is(sDone){
      state := sIdle
    }
  }


  vcr.io.vcr.finish := last

  io.mem <> cache.io.mem
  vcr.io.host <> io.host

}

