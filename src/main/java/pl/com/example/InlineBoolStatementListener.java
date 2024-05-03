package pl.com.example;

import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.misc.Pair;
import pl.com.example.grammar.JavaParser;
import pl.com.example.grammar.JavaParserBaseListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class InlineBoolStatementListener extends JavaParserBaseListener {

    private static final int IF_TOKEN = 22;
    private static final String METHOD_CALL_REGEX = "([_a-zA-Z][_a-zA-Z0-9\\n]+) *\\(\\n*([_a-zA-Z\\n][_a-zA-Z0-9, \\n]+)*\\)";

    private String methodName;

    private JavaParser.ClassBodyContext currentClassBody;

    private LocalSymbols localSymbols;

    public InlineBoolStatementListener() {
        this.localSymbols = new LocalSymbols();
    }

    @Override
    public void enterLocalVariableDeclaration(JavaParser.LocalVariableDeclarationContext ctx) {

        super.enterLocalVariableDeclaration(ctx);

        this.localSymbols.addSymbol(ctx.variableDeclarators().getText(), ctx.typeType().getText());
    }

    @Override
    public void enterMethodDeclaration(JavaParser.MethodDeclarationContext ctx) {
        super.enterMethodDeclaration(ctx);

        String currentMethodName = ctx.identifier().getText();
        if (currentMethodName.equals(methodName)) {
            System.out.println("SUCCESS!");
        }
    }

    @Override
    public void enterClassDeclaration(JavaParser.ClassDeclarationContext ctx) {
        this.currentClassBody = ctx.classBody();
    }

    @Override
    public void exitParExpression(JavaParser.ParExpressionContext ctx) {

        if(!isIfExpression(ctx)){
            return;
        }

        inlineBooleanExpression(ctx);
    }

    private boolean isIfExpression(JavaParser.ParExpressionContext ctx){
        return ((JavaParser.StatementContext)ctx.parent).start.getType() == IF_TOKEN;
    }

    /**
     * Finds the method used within the <b>IF</b> condition
     * and places the result logical operation as the condition.
     * If any operations were performed within the method,
     * then the same operations are placed just before the <b>IF</b> statement
     * @param ctx
     */
    private void inlineBooleanExpression(JavaParser.ParExpressionContext ctx) {
        String expression = ctx.expression().getText();

        Pattern methodCallPattern = Pattern.compile(METHOD_CALL_REGEX);
        Matcher methodCallMatcher = methodCallPattern.matcher(expression);

        List<Method> methods = new ArrayList<>();

        System.out.println("NEW IF EXPR");

        while(methodCallMatcher.find()){
            String[] paramNames = methodCallMatcher.group(2).split(",");
            methods.add(
                    new Method(methodCallMatcher.group(1),
                            Arrays.stream(paramNames)
                                    .map(paramName -> new Pair<>(paramName, localSymbols.getSymbol(paramName)))
                                    .toList())
            );
        }

        List<String> returnExpressions = methods.stream().map(
                method -> getReturnExpression(findMethodInClass(method.getSignature(), currentClassBody))).toList();

        System.out.println(returnExpressions);
    }

    private JavaParser.MethodDeclarationContext findMethodInClass(String targetSignature, JavaParser.ClassBodyContext classBody) {
        for (JavaParser.ClassBodyDeclarationContext decl : classBody.classBodyDeclaration()) {
            if (decl.memberDeclaration() != null && decl.memberDeclaration().methodDeclaration() != null) {
                JavaParser.MethodDeclarationContext method = decl.memberDeclaration().methodDeclaration();
                String methodSignature = createMethodSignature(method);
                if (methodSignature.equals(targetSignature)) {
                    return method;
                }
            }
        }
        return null;
    }

    private String createMethodSignature(JavaParser.MethodDeclarationContext method) {
        String methodName = method.identifier().getText();
        List<String> parameterTypes = new ArrayList<>();
        if(method.formalParameters().formalParameterList() != null) {
            parameterTypes.addAll(method.formalParameters().formalParameterList().formalParameter().stream()
                    .map(param -> param.typeType().getText())
                    .toList());
        }
        return methodName + "(" + String.join(", ", parameterTypes) + ")";
    }

    private String getReturnExpression(JavaParser.MethodDeclarationContext method) {
        for (JavaParser.BlockStatementContext statement : method.methodBody().block().blockStatement()) {
            if (statement.statement() != null && statement.statement().RETURN() != null) {
                return statement.statement().expression().get(0).getText();
            }
        }
        return null;
    }

    class Method {
        String name;
        List<Pair<String, String>> params;

        public Method(String name, List<Pair<String, String>> params) {
            this.name = name;
            this.params = params;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<Pair<String, String>> getParams() {
            return params;
        }

        public void setParams(List<Pair<String, String>> params) {
            this.params = params;
        }

        public String getSignature() {
            StringBuilder signature = new StringBuilder();
            signature.append(name).append("(");
            Iterator<Pair<String,String>> iterator = params.iterator();
            while (iterator.hasNext()){
                signature.append(iterator.next().b);
                if(iterator.hasNext()) {
                    signature.append(", ");
                }
            }
            signature.append(")");
            return signature.toString();
        }
    }
}
