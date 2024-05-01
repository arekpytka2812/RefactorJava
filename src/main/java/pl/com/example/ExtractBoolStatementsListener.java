package pl.com.example;

import org.antlr.v4.runtime.*;
import org.stringtemplate.v4.ST;
import pl.com.example.grammar.JavaParser;
import pl.com.example.grammar.JavaParserBaseListener;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExtractBoolStatementsListener extends JavaParserBaseListener {

    private static final String LOG_OP_REGEX = "\\&\\&|\\|\\|";
    //TODO: ten regex jeszcze czhyba do zgeneralizowania
    private static final String VARIABLES_IN_EXPR_SEPARATION_REGEX = "\\b[A-Za-z][A-Za-z0-9]*\\b";
    private static final int IF_TOKEN = 22;
    private static final int STATIC_TOKEN = 38;
    private static final String LINE = "line";
    public TokenStreamRewriter rewriter;
    private final Integer expandedEnoughExpressionIdentifier;
    private Integer insertIndex = null;
    private Integer functionCounter = 1;
    private final LocalSymbols symbols;
    private boolean isStatic = false;

    public ExtractBoolStatementsListener(
            CommonTokenStream commonTokenStream,
            int expandedEnoughExpressionIdentifier
    ){
        rewriter = new TokenStreamRewriter(commonTokenStream);
        this.expandedEnoughExpressionIdentifier = expandedEnoughExpressionIdentifier;
        this.symbols = new LocalSymbols();
    }

    /**
     * Checks if method is static
     * @param ctx the parse tree
     */
    @Override
    public void enterClassBodyDeclaration(JavaParser.ClassBodyDeclarationContext ctx) {

        super.enterClassBodyDeclaration(ctx);

        this.isStatic = false;

        for(var identifier : ctx.modifier()){
            if(identifier.classOrInterfaceModifier().start.getType() == STATIC_TOKEN){
                this.isStatic = true;
                break;
            }
        }
    }

    /**
     * Clears symbols and collect arguments passed to this function
     * @param ctx the parse tree
     */
    @Override
    public void enterMethodDeclaration(JavaParser.MethodDeclarationContext ctx) {

        super.enterMethodDeclaration(ctx);

        this.symbols.clearSymbols();

        if(ctx.formalParameters().formalParameterList() == null
                || ctx.formalParameters().formalParameterList().isEmpty()
        ){
            return;
        }

        List<JavaParser.FormalParameterContext> parameters = ctx.formalParameters().formalParameterList().formalParameter();

        for(var parameter : parameters){
            this.symbols.addSymbol(parameter.variableDeclaratorId().getText(), parameter.typeType().getText());
        }

    }

    /**
     * Calculates boolean function index
     * @param ctx the parse tree
     */
    @Override
    public void enterMethodBody(JavaParser.MethodBodyContext ctx) {

        super.enterMethodBody(ctx);

        if(insertIndex == null || ctx.stop.getTokenIndex() != insertIndex){
            insertIndex = ctx.stop.getTokenIndex();
        }

    }

    /**
     * Add local variable to symbols
     * @param ctx the parse tree
     */
    @Override
    public void enterLocalVariableDeclaration(JavaParser.LocalVariableDeclarationContext ctx) {

        super.enterLocalVariableDeclaration(ctx);

        this.symbols.addSymbol(ctx.variableDeclarators().getText(), ctx.typeType().getText());
    }

    /**
     * Creates, inserts boolean function and replaces
     * if expression to created method invocation
     * @param ctx the parse tree
     */
    @Override
    public void exitParExpression(JavaParser.ParExpressionContext ctx) {

        if(!isIfExpression(ctx)){
            return;
        }

        if(!isExpandedEnough(ctx.expression().getText())){
            return;
        }

        String functionName = createFunctionName();
        ST boolMethod = createMethodST(functionName, ctx.expression().getText());

        String methodInvocation = createMethodInvocationString(functionName, ctx.expression().getText());

        rewriter.insertAfter(insertIndex, boolMethod.render());
        rewriter.replace(ctx.expression().start, ctx.expression().stop, methodInvocation);
    }

    /**
     * Checks if met par expression is if statement
     * @param ctx
     */
    private boolean isIfExpression(JavaParser.ParExpressionContext ctx){
        return ((JavaParser.StatementContext)ctx.parent).start.getType() == IF_TOKEN;
    }

    /**
     * Check if met if statement is expanded enough to be extracted to function
     * @param expression
     */
    private boolean isExpandedEnough(String expression){

        Pattern logOpPattern = Pattern.compile(LOG_OP_REGEX);
        Matcher logOpMatcher = logOpPattern.matcher(expression);

        int logOpCount = 0;

        while (logOpMatcher.find()) {
            logOpCount++;
        }

        return logOpCount >= expandedEnoughExpressionIdentifier;
    }

    /**
     * Creates boolean function String Template based on functionName and expression
     * @param functionName
     * @param expression
     */
    private ST createMethodST(String functionName, String expression){

        ST boolMethod = new ST("<line>");

        boolMethod
                .add(LINE, "\n\n\t")
                .add(LINE, "private ");

        if(isStatic) {
            boolMethod.add(LINE, "static ");
        }

        boolMethod.add(LINE, "boolean " + functionName + "(");

        List<String> variablesFromExpression = getVariablesListFromExpression(expression);

        String variableType = null;
        boolean firstArgumentInserted = false;

        for(String variable : variablesFromExpression){

            variableType = this.symbols.getSymbol(variable);

            if(variableType == null){
                continue;
            }

            if(!firstArgumentInserted){
                boolMethod.add(LINE, variableType + " " + variable);
                firstArgumentInserted = true;
                continue;
            }

            boolMethod.add(LINE, ", " + variableType + " " + variable);

        }

        boolMethod
                .add(LINE, "){")
                .add(LINE, "\n\t\t")
                .add(LINE, "return ")
                .add(LINE, expression + ";")
                .add(LINE, "\n\t}");

        return boolMethod;
    }

    /**
     * Returns list of String containing variables used in if expression
     * @param expression
     */
    private List<String> getVariablesListFromExpression(String expression){

        Pattern variablesPattern = Pattern.compile(VARIABLES_IN_EXPR_SEPARATION_REGEX);
        Matcher variablesMatcher = variablesPattern.matcher(expression);

        List<String> variables = new ArrayList<>();

        while (variablesMatcher.find()) {
            variables.add(variablesMatcher.group());
        }

        return variables;
    }

    /**
     * Creates function name
     * @return
     */
    private String createFunctionName(){
        String functionName = "boolFunction" + functionCounter.toString();
        functionCounter++;

        return functionName;
    }

    /**
     * Creates method invocation string. This string will replace expression in if statement
     * @param funcName
     * @param expression
     * @return
     */
    private String createMethodInvocationString(String funcName, String expression){

        StringBuilder methodInvocationBuilder = new StringBuilder(funcName + "(");
        List<String> variablesFromExpression = getVariablesListFromExpression(expression);

        boolean firstArgumentInserted = false;

        for(String variable : variablesFromExpression){

            if(!this.symbols.isSymbol(variable)){
                continue;
            }

            if(!firstArgumentInserted) {
                methodInvocationBuilder.append(variable);
                firstArgumentInserted = true;
                continue;
            }

            methodInvocationBuilder.append(", ").append(variable);
        }

        return methodInvocationBuilder.toString();
    }
}
