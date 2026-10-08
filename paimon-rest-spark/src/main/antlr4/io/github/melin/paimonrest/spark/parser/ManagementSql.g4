/*
 * 管理语句的语法扩展。
 *
 * 这份语法只描述「管理 Paimon REST Catalog 的主体、角色与授权」这一组语句，
 * 不重复描述 SQL 查询。运行时它先于 Spark 原生解析器被尝试：
 * 解析成功则执行管理操作，解析失败（ParseCancellationException）则原样交回
 * Spark 的 ParserInterface，因此普通 SQL 的行为不受影响。
 *
 * 之所以必须自带语法：Spark 3.5 的 SqlBase.g4 是封闭的，statement 规则没有
 * 兜底分支，`CREATE PRINCIPAL x` 这类语句在词法/语法阶段就会失败，
 * 无法通过 visitor 拦截。详见 paimon-rest-spark/pom.xml 的说明。
 *
 * 关键字用「字母片段」写法实现大小写不敏感（A: [aA]; CREATE: C R E A T E;），
 * 而不用 ANTLR 的 caseInsensitive 选项：后者对取反字符集的支持有限，
 * 而标识符需要 `~'`'` 这类取反集合。
 *
 * 注意：不要在这里写 @header 声明 package。antlr4-maven-plugin 会依据本文件
 * 相对 src/main/antlr4 的目录层级自动生成 package 语句，两者同时存在会产生
 * 重复的 package 声明而编译失败。包名由目录结构决定。
 */
grammar ManagementSql;

singleStatement
    : statement SEMICOLON* EOF
    ;

statement
    : createPrincipal
    | dropPrincipal
    | alterPrincipal
    | resetPrincipal
    | createPrincipalRole
    | dropPrincipalRole
    | alterPrincipalRole
    | createCatalogRole
    | dropCatalogRole
    | alterCatalogRole
    | grantPrincipalRole
    | revokePrincipalRole
    | grantCatalogRole
    | revokeCatalogRole
    | grantPrivileges
    | revokePrivileges
    | showPrincipals
    | showPrincipalRoles
    | showCatalogRoles
    | showGrants
    | showCatalogs
    ;

// ---------------------------------------------------------------------- DDL：主体
createPrincipal
    : CREATE PRINCIPAL (IF NOT EXISTS)? name=identifier propertyList?
    ;

dropPrincipal
    : DROP PRINCIPAL (IF EXISTS)? name=identifier
    ;

alterPrincipal
    : ALTER PRINCIPAL name=identifier (setPropertyList | unsetPropertyList)
    ;

/** RESET 与 ROTATE 同义：都重置主体凭据并返回一次性的明文密钥。 */
resetPrincipal
    : (RESET | ROTATE) PRINCIPAL name=identifier
    ;

// ---------------------------------------------------------------------- DDL：principal role
createPrincipalRole
    : CREATE PRINCIPAL ROLE (IF NOT EXISTS)? name=identifier propertyList?
    ;

dropPrincipalRole
    : DROP PRINCIPAL ROLE (IF EXISTS)? name=identifier
    ;

alterPrincipalRole
    : ALTER PRINCIPAL ROLE name=identifier (setPropertyList | unsetPropertyList)
    ;

// ---------------------------------------------------------------------- DDL：catalog role
createCatalogRole
    : CREATE CATALOG ROLE (IF NOT EXISTS)? name=identifier IN CATALOG catalog=identifier propertyList?
    ;

dropCatalogRole
    : DROP CATALOG ROLE (IF EXISTS)? name=identifier IN CATALOG catalog=identifier
    ;

alterCatalogRole
    : ALTER CATALOG ROLE name=identifier IN CATALOG catalog=identifier
      (setPropertyList | unsetPropertyList)
    ;

// ---------------------------------------------------------------------- DCL：角色装配
grantPrincipalRole
    : GRANT PRINCIPAL ROLE role=identifier TO PRINCIPAL principal=identifier
    ;

revokePrincipalRole
    : REVOKE PRINCIPAL ROLE role=identifier FROM PRINCIPAL principal=identifier
    ;

grantCatalogRole
    : GRANT CATALOG ROLE role=identifier TO PRINCIPAL ROLE principalRole=identifier
      IN CATALOG catalog=identifier
    ;

revokeCatalogRole
    : REVOKE CATALOG ROLE role=identifier FROM PRINCIPAL ROLE principalRole=identifier
      IN CATALOG catalog=identifier
    ;

// ---------------------------------------------------------------------- DCL：资源授权
/*
 * 目标 catalog role 所属的 catalog 不单独书写，而是从资源描述中取得：
 * `ON TABLE ns.t IN CATALOG paimon` 已明确指出 catalog 是 paimon。
 * `ON CATALOG paimon` 同理。这样避免同一语句里重复两次 catalog 名。
 */
grantPrivileges
    : GRANT privilegeList ON grantResource TO CATALOG ROLE role=identifier
    ;

revokePrivileges
    : REVOKE privilegeList ON grantResource FROM CATALOG ROLE role=identifier
    ;

privilegeList
    : privilege (COMMA privilege)*
    ;

privilege
    : identifier
    ;

grantResource
    : catalogResource
    | namespaceResource
    | tableResource
    | viewResource
    | policyResource
    | semanticModelResource
    ;

catalogResource
    : CATALOG catalog=identifier
    ;

namespaceResource
    : NAMESPACE namespace=multipartIdentifier IN CATALOG catalog=identifier
    ;

tableResource
    : TABLE objectName=multipartIdentifier IN CATALOG catalog=identifier
    ;

viewResource
    : VIEW objectName=multipartIdentifier IN CATALOG catalog=identifier
    ;

policyResource
    : POLICY objectName=multipartIdentifier IN CATALOG catalog=identifier
    ;

semanticModelResource
    : SEMANTIC MODEL objectName=multipartIdentifier IN CATALOG catalog=identifier
    ;

// ---------------------------------------------------------------------- SHOW
showPrincipals
    : SHOW PRINCIPALS (FOR PRINCIPAL ROLE principalRole=identifier)?
    ;

showPrincipalRoles
    : SHOW PRINCIPAL ROLES (FOR PRINCIPAL principal=identifier)?
    ;

showCatalogRoles
    : SHOW CATALOG ROLES IN CATALOG catalog=identifier
    | SHOW CATALOG ROLES FOR PRINCIPAL ROLE principalRole=identifier IN CATALOG catalog=identifier
    ;

showGrants
    : SHOW GRANTS FOR CATALOG ROLE role=identifier IN CATALOG catalog=identifier
    ;

showCatalogs
    : SHOW CATALOGS
    ;

// ---------------------------------------------------------------------- 公共片段
propertyList
    : PROPERTIES LPAREN property (COMMA property)* RPAREN
    ;

setPropertyList
    : SET PROPERTIES LPAREN property (COMMA property)* RPAREN
    ;

unsetPropertyList
    : UNSET PROPERTIES LPAREN propertyKey (COMMA propertyKey)* RPAREN
    ;

property
    : key=propertyKey (EQ value=propertyValue)?
    ;

propertyKey
    : STRING
    | identifier
    ;

propertyValue
    : STRING
    | number
    | identifier
    ;

identifier
    : IDENTIFIER
    | BACKQUOTED_IDENTIFIER
    ;

multipartIdentifier
    : identifier (DOT identifier)*
    ;

number
    : MINUS? DECIMAL_VALUE
    ;

// ---------------------------------------------------------------------- 词法
SEMICOLON  : ';' ;
COMMA      : ',' ;
LPAREN     : '(' ;
RPAREN     : ')' ;
EQ         : '=' ;
DOT        : '.' ;
MINUS      : '-' ;

ALTER      : A L T E R ;
CATALOG    : C A T A L O G ;
CATALOGS   : C A T A L O G S ;
CREATE     : C R E A T E ;
DROP       : D R O P ;
EXISTS     : E X I S T S ;
FOR        : F O R ;
FROM       : F R O M ;
GRANT      : G R A N T ;
GRANTS     : G R A N T S ;
IF         : I F ;
IN         : I N ;
MODEL      : M O D E L ;
NAMESPACE  : N A M E S P A C E ;
NOT        : N O T ;
ON         : O N ;
POLICY     : P O L I C Y ;
PRINCIPAL  : P R I N C I P A L ;
PRINCIPALS : P R I N C I P A L S ;
PROPERTIES : P R O P E R T I E S ;
RESET      : R E S E T ;
REVOKE     : R E V O K E ;
ROLE       : R O L E ;
ROLES      : R O L E S ;
ROTATE     : R O T A T E ;
SEMANTIC   : S E M A N T I C ;
SET        : S E T ;
SHOW       : S H O W ;
TABLE      : T A B L E ;
TO         : T O ;
UNSET      : U N S E T ;
VIEW       : V I E W ;

BACKQUOTED_IDENTIFIER
    : '`' ( '``' | ~'`' )* '`'
    ;

STRING
    : '\'' ( '\'\'' | ~'\'' )* '\''
    | '"' ( '""' | ~'"' )* '"'
    ;

DECIMAL_VALUE
    : DIGIT+ ( '.' DIGIT+ )?
    ;

IDENTIFIER
    : LETTER ( LETTER | DIGIT )*
    ;

fragment DIGIT  : [0-9] ;
fragment LETTER : [A-Za-z_] ;

fragment A : [aA] ;
fragment B : [bB] ;
fragment C : [cC] ;
fragment D : [dD] ;
fragment E : [eE] ;
fragment F : [fF] ;
fragment G : [gG] ;
fragment H : [hH] ;
fragment I : [iI] ;
fragment J : [jJ] ;
fragment K : [kK] ;
fragment L : [lL] ;
fragment M : [mM] ;
fragment N : [nN] ;
fragment O : [oO] ;
fragment P : [pP] ;
fragment Q : [qQ] ;
fragment R : [rR] ;
fragment S : [sS] ;
fragment T : [tT] ;
fragment U : [uU] ;
fragment V : [vV] ;
fragment W : [wW] ;
fragment X : [xX] ;
fragment Y : [yY] ;
fragment Z : [zZ] ;

SPACE
    : [ \t\r\n\u000B\u000C]+ -> skip
    ;

LINE_COMMENT
    : '--' ~[\r\n]* -> skip
    ;

BRACKETED_COMMENT
    : '/*' .*? '*/' -> skip
    ;
