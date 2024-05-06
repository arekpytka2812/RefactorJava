package pl.com.example;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.misc.Pair;
import pl.com.example.grammar.JavaParser;
import pl.com.example.grammar.JavaParserBaseListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class InlineBoolStatementListener extends JavaParserBaseListener {

    private static final int IF_TOKEN = 22;
    private static final String METHOD_CALL_REGEX = "([_a-zA-Z][_a-zA-Z0-9\\n]+) *\\(\\n*([_a-zA-Z\\n][_a-zA-Z0-9, \\n]+)*\\)";
    private JavaParser.ClassBodyContext currentClassBody;
    private final LocalSymbols localSymbols;
    private Integer insertIndex;
    public TokenStreamRewriter rewriter;
    public Set<JavaParser.MethodDeclarationContext> calledMethods;

    public InlineBoolStatementListener(
            CommonTokenStream commonTokenStream
    ) {
        this.rewriter = new TokenStreamRewriter(commonTokenStream);
        this.localSymbols = new LocalSymbols();
        this.calledMethods = new HashSet<>();
    }

    @Override
    public void enterLocalVariableDeclaration(JavaParser.LocalVariableDeclarationContext ctx) {

        super.enterLocalVariableDeclaration(ctx);

        this.localSymbols.addSymbol(ctx.variableDeclarators().getText(), ctx.typeType().getText());
    }

    @Override
    public void enterClassDeclaration(JavaParser.ClassDeclarationContext ctx) {
        this.currentClassBody = ctx.classBody();
    }

    @Override
    public void exitClassDeclaration(JavaParser.ClassDeclarationContext ctx) {
        this.calledMethods.forEach(call ->
                rewriter.delete(call.getParent().getParent().start.getTokenIndex()-2,call.stop.getTokenIndex())
        );
    }

    @Override
    public void exitParExpression(JavaParser.ParExpressionContext ctx) {

        if(!isIfExpression(ctx)){
            return;
        }

        if(insertIndex == null || ctx.stop.getTokenIndex() != insertIndex){
            insertIndex = ((JavaParser.StatementContext)ctx.parent).start.getTokenIndex();
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

        List<Method> methodCalls = new ArrayList<>();

        System.out.println("NEW IF EXPR");

        while(methodCallMatcher.find()){
            String[] argNames = methodCallMatcher.group(2).split(",");
            methodCalls.add(
                    new Method(methodCallMatcher.group(1),
                            Arrays.stream(argNames)
                                    .map(argName -> new Pair<>(argName, localSymbols.getSymbol(argName)))
                                    .toList())
            );
        }
        Map<Method, JavaParser.MethodDeclarationContext> callToDeclaration = methodCalls.stream().collect(Collectors.toMap(
                Function.identity(),
                method -> findMethodInClass(method, currentClassBody)
        ));

        this.calledMethods.addAll(new HashSet<>(callToDeclaration.values()));


        callToDeclaration.forEach(
                (call, declaration) -> {
                    rewriter.replace(ctx.expression().start, ctx.expression().stop, getReturnExpression(call, declaration));
//                    rewriter.insertBefore(insertIndex, extractMethodBody(declaration));
                }
            );
    }

    private JavaParser.MethodDeclarationContext findMethodInClass(Method targetMethod, JavaParser.ClassBodyContext classBody) {
        for (JavaParser.ClassBodyDeclarationContext decl : classBody.classBodyDeclaration()) {
            if (decl.memberDeclaration() != null && decl.memberDeclaration().methodDeclaration() != null) {
                JavaParser.MethodDeclarationContext method = decl.memberDeclaration().methodDeclaration();
                if (compareMethodSignatures(targetMethod, method)) {
                    System.out.println(targetMethod.getSignature());
                    return method;
                }
            }
        }
        return null;
    }

    private boolean compareMethodSignatures(Method methodCall, JavaParser.MethodDeclarationContext method) {
        if (!methodCall.name.equals(method.identifier().getText())) {
            return false;
        }
        JavaParser.FormalParameterListContext paramContext = method.formalParameters().formalParameterList();
        List<JavaParser.FormalParameterContext> formalParameterContexts = paramContext == null ?
                Collections.emptyList()
                :
                paramContext.formalParameter();

        List<Pair<String, String>> methodCallParams = methodCall.params;
        if (formalParameterContexts.size() != methodCallParams.size()) {
            return false;
        }
        for (int i = 0; i < methodCallParams.size(); i++) {
            if (!methodCallParams.get(i).b.equals(formalParameterContexts.get(i).typeType().getText())) {
                return false;
            }
        }
        return true;
    }

    private String extractMethodBody(JavaParser.MethodDeclarationContext method) {
        String body = rewriter.getTokenStream().getText(Interval.of(
                method.methodBody().start.getTokenIndex(),
                method.methodBody().stop.getTokenIndex())
        );
        return body.substring(1, body.indexOf("return")) + "\n\t";
    }

    private String getReturnExpression(Method call, JavaParser.MethodDeclarationContext declaration) {
        for (JavaParser.BlockStatementContext statement : declaration.methodBody().block().blockStatement()) {
            if (statement.statement() != null && statement.statement().RETURN() != null) {

                return swapParams(rewriter.getTokenStream().getText(Interval.of(
                        statement.statement().expression().get(0).start.getTokenIndex(),
                        statement.statement().expression().get(0).stop.getTokenIndex()
                )), call, declaration);
            }
        }
        return null;
    }

    private String swapParams(String methodBody, Method call, JavaParser.MethodDeclarationContext declaration) {

        List<String> formals = declaration.formalParameters().formalParameterList().formalParameter().stream()
                .map(rule -> rule.variableDeclaratorId().identifier().getText()).toList();
        List<String>  actual = call.params.stream().map(param -> param.a).toList();

        if(formals.isEmpty()) {
            return methodBody;
        }

        Map<String, String> formalToActual = IntStream.range(0, formals.size()).boxed().collect(Collectors.toMap(
                formals::get,
                actual::get
        ));

        String result = methodBody;

        for (int i = 0; i < formals.size(); i++) {
            result = result.replaceAll(formals.get(i), actual.get(i));
        }
        return result;
    }

    static class Method {
        private String name;
        private List<Pair<String, String>> params;

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
