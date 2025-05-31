JAVA_FILE = Main.java
CLASS_FILE = Main.class
MAIN_CLASS = Main

all: compile

compile: $(CLASS_FILE)

$(CLASS_FILE): $(JAVA_FILE)
	javac $(JAVA_FILE)

run: $(CLASS_FILE)
	java $(MAIN_CLASS)

run-stress: $(CLASS_FILE)
	@echo "Executando simulação com stress"
	java $(MAIN_CLASS)

clean:
	rm -f *.class

.PHONY: all compile run run-stress clean help demo