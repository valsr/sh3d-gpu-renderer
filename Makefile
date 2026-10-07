SH3D_JARS := /usr/share/java/sweethome3d
J3D_JARS  := /usr/lib/sweethome3d/java3d-1.5
BLENDER   ?= blender
# Folder where the renderer is installed, beside the libraries of Sweet Home 3D
INSTALL_DIR ?= /usr/lib/sweethome3d/gpu-renderer

empty :=
space := $(empty) $(empty)
CP := $(subst $(space),:,$(wildcard $(SH3D_JARS)/*.jar) $(wildcard $(J3D_JARS)/*.jar))

# Same options as Sweet Home 3D launcher, Java 3D needing a display even to build scenes
JAVA_OPTS := -Djava.library.path=$(J3D_JARS) \
  --add-opens=java.desktop/sun.awt=ALL-UNNAMED

MAIN_SRC := $(shell find src/main/java -name '*.java' 2>/dev/null)
TEST_SRC := $(shell find src/test/java -name '*.java' 2>/dev/null)
RESOURCES := $(shell find src/main/resources -type f 2>/dev/null)
TESTS := $(patsubst src/test/java/%.java,%,$(shell find src/test/java -name '*Test.java' 2>/dev/null))

JAR := build/gpu-renderer.jar

.PHONY: all test test-java test-worker render install uninstall clean

all: $(JAR)

$(JAR): $(MAIN_SRC) $(RESOURCES) src/main/manifest.mf
	rm -rf build/classes && mkdir -p build/classes
	javac -nowarn --release 17 -cp "$(CP)" -d build/classes $(MAIN_SRC)
	cp -r src/main/resources/. build/classes/
	jar cfm $@ src/main/manifest.mf -C build/classes .

build/test-classes/.stamp: $(JAR) $(TEST_SRC)
	rm -rf build/test-classes && mkdir -p build/test-classes
	javac -nowarn --release 17 -cp "$(JAR):$(CP)" -d build/test-classes $(TEST_SRC)
	touch $@

test: test-java test-worker

# Each *Test class has a main that exits non-zero on failure
test-java: build/test-classes/.stamp
	@for t in $(subst /,.,$(TESTS)); do \
	  echo "== $$t"; \
	  java $(JAVA_OPTS) -Dsh3d.gpurenderer.blender=$(BLENDER) \
	    -cp "build/test-classes:$(JAR):$(CP)" $$t || exit 1; \
	done

test-worker:
	$(BLENDER) -b --factory-startup --python-exit-code 1 --python src/test/python/test_worker.py

# Doesn't build the jar, to avoid files owned by root in build folder when run with sudo
install:
	@test -f $(JAR) || { echo "Run make before make install"; exit 1; }
	install -Dm644 $(JAR) $(DESTDIR)$(INSTALL_DIR)/gpu-renderer.jar
	@echo "Installed. Start Sweet Home 3D with the Java option:"
	@echo "  -javaagent:$(INSTALL_DIR)/gpu-renderer.jar"

uninstall:
	rm -f $(DESTDIR)$(INSTALL_DIR)/gpu-renderer.jar
	-rmdir $(DESTDIR)$(INSTALL_DIR)

clean:
	rm -rf build

# Renders a home from the command line, for example: make render ARGS="Studio build/studio.png 1280 720 HIGH"
render: build/test-classes/.stamp
	java $(JAVA_OPTS) -Dsh3d.gpurenderer.blender=$(BLENDER) \
	  -cp "build/test-classes:$(JAR):$(CP)" sh3d.gpurenderer.RenderHomeTool $(ARGS)
