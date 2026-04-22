

.PHONY: sim-verilog emu clean

sim-verilog:
	mill 'wiredrv[6.6.0].runMain' wiredrv.Test

emu: sim-verilog
	@$(MAKE) -C difftest emu WITH_CHISELDB=0 WITH_CONSTANTIN=0

clean:
	rm -rf build
	rm -rf generated
	rm -rf wave
	rm -rf UnitTest.mk
