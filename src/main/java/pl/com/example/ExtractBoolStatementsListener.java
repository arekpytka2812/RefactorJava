package pl.com.example;

import org.antlr.v4.runtime.*;
import org.stringtemplate.v4.ST;
import pl.com.example.grammar.JavaLexer;
import pl.com.example.grammar.JavaParser;
import pl.com.example.grammar.JavaParserBaseListener;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExtractBoolStatementsListener extends JavaParserBaseListener {

    private static final String LOG_OP_REGEX = "\\&\\&|\\|\\|";
    private static final String IF_KEYWORD = "if";
    public static final String LINE = "line";
    private final CommonTokenStream tokStream;
    public TokenStreamRewriter rewriter;

    private JavaParser parser;
    private JavaLexer lexer;

    private Integer insertIndex = null;

    private Integer funcCounter = 1;

    private Integer extractingMethodIndex = -1;

    public ExtractBoolStatementsListener(CommonTokenStream commonTokenStream, JavaParser parser, JavaLexer lexer){
        this.tokStream = commonTokenStream;
        rewriter = new TokenStreamRewriter(commonTokenStream);
        this.lexer = lexer;
        this.parser = parser;
    }

    @Override
    public void exitParExpression(JavaParser.ParExpressionContext ctx) {

        // sprawdzenie czy rozpatrujemy ifa
        if(!ctx.parent.getChild(0).getText().equals(IF_KEYWORD)){
            return;
        }

        Pattern logOpPattern = Pattern.compile(LOG_OP_REGEX);

        Matcher logOpMatcher = logOpPattern.matcher(ctx.expression().getText());

        // Count the occurrences
        int logOpCount = 0;

        while (logOpMatcher.find()) {
            logOpCount++;
        }

        if(logOpCount < 2){
            return;
        }

        RuleContext parent = ctx.parent;

        while(parent.getClass() != JavaParser.MethodBodyContext.class){

            parent = parent.parent;
        }

        JavaParser.ClassBodyDeclarationContext classBodyDeclarationContext = (JavaParser.ClassBodyDeclarationContext) parent.parent.parent.parent;

        boolean isStatic = false;

        for(JavaParser.ModifierContext modifier : classBodyDeclarationContext.modifier()){
            isStatic = modifier.classOrInterfaceModifier().getText().equals("static");

            if(isStatic){
                break;
            }
        }

        ST boolMethod = new ST("<line>");

        String funcName = "boolFunc" + funcCounter.toString();

        boolMethod
            .add(LINE, "\n\n\t")
            .add(LINE, "private ");

        if(isStatic) {
            boolMethod.add(LINE, "static ");
        }

        boolMethod.add(LINE, "boolean ")
            .add(LINE,  funcName+ "(")
            //arguments
            .add(LINE, "){")
            .add(LINE, "\n\t\t")
            .add(LINE, "return ")
            .add(LINE, ctx.expression().getText() + ";")
            .add(LINE, "\n\t}");


        funcCounter++;

        if(insertIndex == null || ((JavaParser.MethodBodyContext) parent).stop.getTokenIndex() != insertIndex){
            insertIndex = ((JavaParser.MethodBodyContext) parent).stop.getTokenIndex();
        }

        rewriter.insertAfter(insertIndex, boolMethod.render());

        rewriter.replace(ctx.expression().start, ctx.expression().stop, funcName + "()");
    }


}
