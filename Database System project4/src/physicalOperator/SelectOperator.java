package physicalOperator;
import project.*;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;

import IO.BinaryWriter;
import IO.DirectWriter;
import IO.TupleWriter;
import net.sf.jsqlparser.expression.Expression;
/**
 * Select operator to execute the select operation.
 * It grabs the next tuple from its child and returns
 * tuple that passes the selection condition.
 * 
 * @author Chengcheng Ji (cj368) and Pei Xu (px29)
 */
public class SelectOperator extends Operator {
    private Operator child;
    private Expression ex;

	
	public SelectOperator(Operator child, Expression ex) {
      this.child=child;
      this.ex=ex;
	}
	/**
	 * Method to obtain the next tuple from its child and check
	 * 
	 * @return (Tuple) the tuple matches the selection condition
	 */
	@Override
	public Tuple getNextTuple() throws Exception{		
	Tuple tu;
	while((tu= child.getNextTuple())!=null) {
		if(ex == null) {return tu;}
		else{
			conditionEvaluator eva= new conditionEvaluator(tu,ex);
			if (eva.getResult()){return tu;}
		}
	}	
	return null;
	}

	/**
	 * Method to reset
	 */
	@Override
	public void reset() throws Exception{
    child.reset();		
	}

	/**
	 * Method to dump the results
	 */
	@Override
	public void dump() throws Exception {
	    Tuple tu;
        TupleWriter writer= new BinaryWriter();
        TupleWriter writerReadable = null;
        if (QueryPlan.debuggingMode) {writerReadable = new DirectWriter();}
        
    	while ((tu=this.getNextTuple())!=null) {
    		writer.writeNext(tu);
    		if (QueryPlan.debuggingMode){writerReadable.writeNext(tu);}
    	}
    	writer.close();
    	if (QueryPlan.debuggingMode){writerReadable.close();}
		QueryPlan.nextQuery();
	}
	@Override
	public void setLeftChild(Operator child) {
		this.child = (Operator)child;
	}
	@Override
	public void setRightChild(Operator child) {
		
	}
	@Override
	public Operator getLeftChild() {
		return this.child;
	}
	@Override
	public Operator getRightChild() {
		return null;
	};
	public Expression getExpression(){
		return this.ex;
	}
	@Override
	public void reset(int index) throws Exception {
		// TODO Auto-generated method stub
		
	}
}
