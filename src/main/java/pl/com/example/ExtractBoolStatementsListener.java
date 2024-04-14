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

    private final CommonTokenStream tokStream;
    public TokenStreamRewriter rewriter;

    private JavaParser parser;
    private JavaLexer lexer;

    private Integer insertIndex = null;

    private Integer funcCounter = 1;

    public ExtractBoolStatementsListener(CommonTokenStream commonTokenStream, JavaParser parser, JavaLexer lexer){
        this.tokStream = commonTokenStream;
        rewriter = new TokenStreamRewriter(commonTokenStream);
        this.lexer = lexer;
        this.parser = parser;
    }

    @Override
    public void exitParExpression(JavaParser.ParExpressionContext ctx) {

        // sprawdzenie czy rozpatrujemy ifa
        if(!ctx.parent.getChild(0).getText().equals("if")){
            return;
        }

        Pattern pattern = Pattern.compile("\\&\\&|\\|\\|");
        Matcher matcher = pattern.matcher(ctx.expression().getText());

        // Count the occurrences
        int count = 0;
        while (matcher.find()) {
            count++;
        }

        if(count < 2){
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
        }

        ST boolMethod = new ST("<line>");

        if(isStatic){
            boolMethod
                    .add("line", "\n\n\t")
                    .add("line", "private ")
                    .add("line", "static ")
                    .add("line", "boolean ")
                    .add("line", "boolFunc")
                    .add("line", funcCounter.toString() + "(")
                    //arguments
                    .add("line", "){")
                    .add("line", "\n\t\t")
                    .add("line", "return ")
                    .add("line", ctx.expression().getText() + ";")
                    .add("line", "\n\t}");
        }
        else {
            boolMethod
                    .add("line", "\n\n\t")
                    .add("line", "private ")
                    .add("line", "boolean ")
                    .add("line", "boolFunc")
                    .add("line", funcCounter.toString() + "(")
                    .add("line", "){")
                    .add("line", "\n\t\t")
                    .add("line", "return ")
                    .add("line", ctx.expression().getText() + ";")
                    .add("line", "\n\t}");
        }

        funcCounter++;

        if(insertIndex == null){
            insertIndex = ((JavaParser.MethodBodyContext) parent).stop.getTokenIndex();
        }

        List<Token> tokenList = tokStream.getTokens(((JavaParser.MethodBodyContext) parent).stop.getTokenIndex(), ((JavaParser.MethodBodyContext) parent).stop.getTokenIndex());

        System.out.println(tokenList.get(tokenList.size() - 1));

        rewriter.insertAfter(insertIndex, boolMethod.render());
    }


}
