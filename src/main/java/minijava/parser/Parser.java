package minijava.parser;

import minijava.lexer.Lexer;
import minijava.token.Terminal;
import minijava.token.Token;

import static minijava.token.Terminal.INT;
import static minijava.token.Terminal.SEMICOLON;

public class Parser {
    private Token currentToken;
    private Lexer lexer;

    public Parser(Lexer lexer){
        this.lexer = lexer;
    }

    private void consumeToken()
    {
        this.currentToken = lexer.next();
    }

    private void expectTokenAndConsume(Terminal terminal){
        if(currentToken.isTerminal(terminal)){
            throw new ParserError("");
        }
        consumeToken();
    }

    private boolean isCurrentTokenTypeOf(Terminal terminal){
        if(currentToken.isTerminal(terminal)){
            return true;
        }
        return false;
    }

    private boolean isCurrentTokenNotTypeOf(Terminal terminal){
        return !isCurrentTokenTypeOf(terminal);
    }

    public void parse(){
        consumeToken();
        parseProgramm();
    }

    /**
     * Program -> ClassDeclaration*
     */
    private void parseProgramm(){
        while(isCurrentTokenNotTypeOf(Terminal.EOF))
        {
            parseClassDeclaration();
        }
        expectTokenAndConsume(Terminal.EOF);
    }

    /**
     *  ClassDeclaration -> class IDENT { ClassMember* }
     */
    private void parseClassDeclaration(){
        expectTokenAndConsume(Terminal.CLASS);
        expectTokenAndConsume(Terminal.IDENT);
        expectTokenAndConsume(Terminal.LCURLY);
        while(isCurrentTokenNotTypeOf(Terminal.RCURLY) && isCurrentTokenNotTypeOf(Terminal.EOF)){
            parseClassMember();
        }
        expectTokenAndConsume(Terminal.RCURLY);
    }

    /**
     * ClassMember -> Field | Method | MainMethod
     */
    private void parseClassMember(){
        expectTokenAndConsume(Terminal.PUBLIC);
        if(isCurrentTokenTypeOf(Terminal.STATIC)){

        }
        else {
            //isCurrentTokenTypeOf(Terminal)
        }
    }

    private void parseField(){

    }

    private void parseMainMethod(){

    }

    private void parseMethod(){

    }

    /**
     * Parameters -> Parameter | Parameter , Parameters
     */
    private void parseParameters(){
        parseParameter();
        if(isCurrentTokenTypeOf(Terminal.COMMA)){
            parseParameters();
        }
    }

    /**
     * Parameter -> Type IDENT
     */
    private void parseParameter(){
        parseType();
        expectTokenAndConsume(Terminal.IDENT);
    }

    /**
     * Type -> BasicType | BasicType []
     */
    private void parseType(){
        parseBasicType();
        if(isCurrentTokenTypeOf(Terminal.LBRACKET)){
            expectTokenAndConsume(Terminal.LBRACKET);
            expectTokenAndConsume(Terminal.RBRACKET);
        }
    }

    /**
     * BasicType -> int | boolean | void | IDENT
     */
    private void parseBasicType(){
        switch(currentToken.terminal){
            case INT:
            case BOOLEAN:
            case VOID:
            case IDENT:
                consumeToken();
                break;
            default:
                throw new ParserError("");
        }
    }

    /**
     * Statement -> Block | EmptyStatement | IfStatement | ExpressionStatement | WhileStatement | ReturnStatement
     */
    private void parseStatement(){
        switch(currentToken.terminal){
            case LCURLY:
                parseBlock();
                break;
            case SEMICOLON:
                parseEmptyStatement();
                break;
            case IF:
                parseIfStatement();
                break;
            case WHILE:
                parseWhileStatement();
                break;
            case RETURN:
                parseReturnStatement();
                break;
            default:
                parseExpression();
                break;
        }
    }

    /**
     * Block -> { BlockStatement* }
     */
    private void parseBlock(){
        expectTokenAndConsume(Terminal.LCURLY);
        while(isCurrentTokenNotTypeOf(Terminal.RCURLY) && isCurrentTokenNotTypeOf(Terminal.EOF)){
            parseBlockStatement();
        }
        expectTokenAndConsume(Terminal.RCURLY);
    }

    /**
     * BlockStatement -> Statement | LocalVariableDeclarationStatement
     */
    private void parseBlockStatement(){
        switch(currentToken.terminal){
            case INT:
            case BOOLEAN:
            case VOID:
            case IDENT:
                parseLocalVariableDeclarationStatement();
                break;
            default:
                parseStatement();
                break;
        }
    }

    /**
     * LocalVariableDeclarationStatement -> Type IDENT (= Expression)? ;
     */
    private void parseLocalVariableDeclarationStatement(){
        parseType();
        expectTokenAndConsume(Terminal.IDENT);
        if(isCurrentTokenTypeOf(Terminal.EQUAL_SIGN)){
            expectTokenAndConsume(Terminal.EQUAL_SIGN);
            parseExpression();
        }
        expectTokenAndConsume(Terminal.SEMICOLON);
    }

    /**
     * EmptyStatement -> ;
     */
    private void parseEmptyStatement(){
        expectTokenAndConsume(Terminal.SEMICOLON);
    }

    /**
     * WhileStatement -> while ( Expression ) Statement
     */
    private void parseWhileStatement(){
        expectTokenAndConsume(Terminal.WHILE);
        expectTokenAndConsume(Terminal.LPAREN);
        parseExpression();
        expectTokenAndConsume(Terminal.RPAREN);
        parseStatement();
    }

    /**
     * IfStatement -> if ( Expression ) Statement (else Statement)?
     */
    private void parseIfStatement(){
        expectTokenAndConsume(Terminal.IF);
        expectTokenAndConsume(Terminal.LPAREN);
        parseExpression();
        expectTokenAndConsume(Terminal.RPAREN);
        parseStatement();
        if(isCurrentTokenTypeOf(Terminal.ELSE)){
            expectTokenAndConsume(Terminal.ELSE);
            parseStatement();
        }
    }

    /**
     * ExpressionStatement -> Expression ;
     */
    private void parseExpressionStatement(){
        parseExpression();
        expectTokenAndConsume(Terminal.SEMICOLON);
    }

    /**
     * ReturnStatement -> return Expression? ;
     */
    private void parseReturnStatement(){
        expectTokenAndConsume(Terminal.RETURN);
        if(isCurrentTokenNotTypeOf(Terminal.SEMICOLON)){
            parseExpression();
        }
        expectTokenAndConsume(Terminal.SEMICOLON);
    }

    /**
     * Expression -> AssignmentExpression
     */
    private void parseExpression(){
        parseAssignmentExpression();
    }

    /**
     * AssignmentExpression -> LogicalOrExpression (= AssignmentExpression)?
     */
    private void parseAssignmentExpression(){
        parseLogicalOrExpression();
        if(isCurrentTokenTypeOf(Terminal.EQUAL_SIGN)){
            expectTokenAndConsume(Terminal.EQUAL_SIGN);
            parseAssignmentExpression();
        }
    }

    /**
     * LogicalOrExpression -> (LogicalOrExpression ||)? LogicalAndExpression
     */
    private void parseLogicalOrExpression(){

    }

    private void parseLogicalAndExpression(){

    }

    private void parseEqualityExpression(){

    }

    private void parseRelationalExpression(){

    }

    private void parseAdditiveExpression(){

    }

    private void parseMultiplicativeExpression(){

    }

    private void parseUnaryExpression(){

    }

    private void parsePostfixExpression(){

    }

    private void parsePostfixOp(){

    }

    private void parseMethodInvocation(){

    }

    private void parseFieldAccess(){

    }

    private void parseArrayAccess(){

    }

    private void parseArguments(){

    }

    private void parsePrimaryExpression(){

    }

    private void parseNewObjectExpression(){

    }

    private void parseNewArrayExpression(){

    }
}