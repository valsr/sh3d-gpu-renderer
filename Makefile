SH3D_JARS := /usr/share/java/sweethome3d
J3D_JARS  := /usr/lib/sweethome3d/java3d-1.5
BLENDER   ?= blender

empty :=
space := $(empty) $(empty)
CP := $(subst $(space),:,$(wildcard $(SH3D_JARS)/*.jar) $(wildcard $(J3D_JARS)/*.jar))

MAIN_SRC := $(shell find src/main/java -name '*.java' 2>/dev/null)
TEST_SRC := $(shell find src/test/java -name '*.java' 2>/dev/null)
RESOURCES := $(shell find src/main/resources -type f 2>/dev/null)
TESTS := $(patsubst src/test/java/%.java,%,$(shell find src/test/java -name '*Test.java' 2>/dev/null))

JAR := build/gpu-renderer.jar

.PHONY: all test test-java test-worker clean

all: $(JAR)

$(JAR): $(MAIN_SRC) $(RESOURCES)
	rm -rf build/classes && mkdir -p build/classes
	javac -nowarn --release 17 -cp "$(CP)" -d build/classes $(MAIN_SRC)
	cp -r src/main/resources/. build/classes/
	jar cf $@ -C build/classes .

build/test-classes/.stamp: $(JAR) $(TEST_SRC)
	rm -rf build/test-classes && mkdir -p build/test-classes
	javac -nowarn --release 17 -cp "$(JAR):$(CP)" -d build/test-classes $(TEST_SRC)
	touch $@

test: test-java test-worker

# Each *Test class has a main that exits non-zero on failure
test-java: build/test-classes/.stamp
	@for t in $(subst /,.,$(TESTS)); do \
	  echo "== $$t"; \
	  java -Djava.awt.headless=true -Dsh3d.gpurenderer.blender=$(BLENDER) \
	    -cp "build/test-classes:$(JAR):$(CP)" $$t || exit 1; \
	done

test-worker:
	$(BLENDER) -b --factory-startup --python-exit-code 1 --python src/test/python/test_worker.py

clean:
	rm -rf build
