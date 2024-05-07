package pl.com.example;

import org.antlr.v4.runtime.*;
import org.stringtemplate.v4.ST;
import pl.com.example.grammar.JavaParser;
import pl.com.example.grammar.JavaParserBaseListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExtractBoolStatementsListener extends JavaParserBaseListener {

    private static final String LOG_OP_REGEX = "\\&\\&|\\|\\|";
    //TODO: ten regex jeszcze czhyba do zgeneralizowania
    private static final String STATEMENTS_WITHOUT_LOG_OP_REGEX = "(?<=^|[\\s&|^!]|(==)|(=>)|(<=)|(!=)|[><])([_a-zA-Z\"'][_a-zA-Z0-9\\.()\"']*)(?=[\\s&|^!]|(==)|(=>)|(<=)|(!=)|[><=]|$)";
    private static final String STRING_OR_CHAR_REGEX = "^(('[^']*'$)|(\"[^\"]*\"))";
    private static final String THIS_STRING = "this";
    private static final int IF_TOKEN = 22;
    private static final int STATIC_TOKEN = 38;
    private static final String LINE = "line";
    public TokenStreamRewriter rewriter;
    private final int expandedEnoughExpressionIdentifier;
    private Integer insertIndex = null;
    private int functionCounter = 1;
    private final LocalSymbols symbols;
    private boolean isStatic = false;

    private List<String> statementsFromExpressionList;

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

        var variables = ctx.variableDeclarators().variableDeclarator();

        for(var variable : variables){
            this.symbols.addSymbol(variable.variableDeclaratorId().getText(), ctx.typeType().getText());
        }
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

        String methodInvocation = createMethodInvocationString(functionName);

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

        String[] splitLogicOperations = expression.split(LOG_OP_REGEX);

        this.statementsFromExpressionList = new ArrayList<>(Arrays.asList(splitLogicOperations));

        return this.statementsFromExpressionList.size() >= expandedEnoughExpressionIdentifier;
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

        List<String> variablesFromExpression = getValidVariablesListFromExpression();

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
     */
    private List<String> getValidVariablesListFromExpression(){

        List<String> validVariables = new ArrayList<>();

        Pattern stringOrCharPattern = Pattern.compile(STRING_OR_CHAR_REGEX);
        Pattern statementsWithoutLogOpPattern = Pattern.compile(STATEMENTS_WITHOUT_LOG_OP_REGEX);

        // statements split by logical operators: "||" or "&&"
        for(String statement : this.statementsFromExpressionList){

            // trimming unnecessary parentheses
            String trimmedStatement = trimParenthesesIfExists(statement);
            Matcher statementsWithoutLogOpMatcher = statementsWithoutLogOpPattern.matcher(trimmedStatement);

            // extracting statements with comparison operators
            // !=. ==. =>, >, <, <=
            while(statementsWithoutLogOpMatcher.find()){
                String pureStatement = statementsWithoutLogOpMatcher.group();
                Matcher stringOrCharMatcher = stringOrCharPattern.matcher(pureStatement);

                // if its char or string we skip
                if(stringOrCharMatcher.find()){
                    continue;
                }

                String[] possibleCascadeFunctionCalls = pureStatement.split("\\.");

                // no cascade reference or invocation -> pure variable name
                if(possibleCascadeFunctionCalls.length < 2){

                    // prevents from passing same variable more than once
                    if(!validVariables.contains(pureStatement)){
                        validVariables.add(pureStatement);
                    }

                    continue;
                }

                // if first letter of first var is capital, then it is either static
                // method, or static variable
                if(Character.isUpperCase(possibleCascadeFunctionCalls[0].charAt(0))){
                    continue;
                }

                // if overriding class variable and reference by "this"
                // such variable wont be passed as valid local variable
                if(possibleCascadeFunctionCalls[0].equals(THIS_STRING)){
                    continue;
                }

                // prevents from passing same variable more than once
                if(!validVariables.contains(possibleCascadeFunctionCalls[0])){
                    validVariables.add(possibleCascadeFunctionCalls[0]);
                }

            }

        }

        return validVariables;
    }

    private String trimParenthesesIfExists(String variable) {

        while(variable.startsWith("(")){
            variable = variable.substring(1);
        }

        while(variable.endsWith(")")){
            if(variable.charAt(variable.length() - 2) != '('){
                variable = variable.substring(0, variable.length() - 1);
            }
            else {
                break;
            }
        }

        return variable;
    }

    /**
     * Creates function name
     * @return
     */
    private String createFunctionName(){
        String functionName = "boolFunction" + functionCounter;
        functionCounter++;

        return functionName;
    }

    /**
     * Creates method invocation string. This string will replace expression in if statement
     * @param funcName
     * @return
     */
    private String createMethodInvocationString(String funcName){

        StringBuilder methodInvocationBuilder = new StringBuilder(funcName + "(");
        List<String> variablesFromExpression = getValidVariablesListFromExpression();

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
