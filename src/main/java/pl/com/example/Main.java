package pl.com.example;


import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import pl.com.example.grammar.JavaLexer;
import pl.com.example.grammar.JavaParser;

import java.io.FileWriter;
import java.io.IOException;

public class Main {
    public static void main(String[] args) {

        CharStream inp = null;

        try {
            inp =  CharStreams.fromFileName("./src/main/java/pl/com/example/input.txt");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        JavaLexer lex = new JavaLexer(inp);
        CommonTokenStream tokens = new CommonTokenStream(lex);
        JavaParser par = new JavaParser(tokens);

        ParseTree tree = par.compilationUnit();
        ParseTreeWalker walker = new ParseTreeWalker();

        ExtractBoolStatementsListener extractBoolStatementsListener = new ExtractBoolStatementsListener(tokens, par, lex);

        walker.walk(extractBoolStatementsListener, tree);

        try {
            var wr = new FileWriter("wy.java");
            wr.write(extractBoolStatementsListener.rewriter.getText());
            wr.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}