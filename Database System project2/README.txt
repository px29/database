The top level class is the QueryPlan.

We use a processWhere visitor which implements the ExpressionVisitor to visit the whole 
expression in the where clause. 

The visitor has two hashMap<String, Expression> fields. One is SelectEx which points from a table name to the extracted expression that is only related to this table like ¡°S.A=1 or S.A=S.B¡±. The other is JoinEx which points form a table name to the extracted expression that joins this table with another table like ¡°S.A=R.B¡±. Also, Expression without columns like ¡°1=1¡± is attributed to the first table

For the join expression, the table which is the key always lies in the right compared to another related table. For example, Select * From A B C Where A.1=B.1 and B.1=C.1 and A.1=C.1. In the hashmap,the A points to null. B points to A.1=B.1 C points to B.1=C.1 and A.1=C.1. 

For ¡°and expression¡±, simply visit the left expression and right expression.
For methods to visit ¡°= != < <= >= >¡±, determine the left expression and right expression. 
If either expression is a number or the table names of both expressions are the same. This 
expression is a select condition. Add it to the corresponding hashmap.
Else, it¡¯s join condition. Add it to the corresponding hashmap.
 
