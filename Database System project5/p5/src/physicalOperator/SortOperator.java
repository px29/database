package physicalOperator;
import project.*;
import queryPlanBuilder.PhysicalPlanBuilder;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

import IO.BinaryWriter;
import IO.DirectWriter;
import IO.TupleWriter;
import net.sf.jsqlparser.expression.Expression;

/**
 * Sort operator to sort data in ascending order according to 
 * a subset of required columns for ordering. Then sort the rest
 * columns.
 * 
 * @author Chengcheng Ji (cj368), Pei Xu (px29) and Ella Xue (ex32)
 */
public class SortOperator extends Operator {
	private Operator child;
	private ArrayList<Tuple> sorted_tuples;
	private ArrayList<SchemaPair> schema_pair;
	private boolean organized = false;
	private int count = 0;
	private Integer orderByIndex[];
	
	public SortOperator(Operator child, ArrayList<SchemaPair> schema_pair) throws Exception {
		this.child = child;
		this.schema_pair = schema_pair;
	}
	
	/**
	 * Method to obtain a tuple sorted in ascending order
	 * 
	 * @return (Tuple) the tuple sorted according to the required columns
	 */
	@Override
	public Tuple getNextTuple() throws Exception {
		if(organized == false) {
			this.sorted_tuples = organize(child, schema_pair);
			organized = true;
		}
		if (sorted_tuples.size() != count) {
			count++;
			return sorted_tuples.get(count-1);
		}
		else return null;
	}

	/**
	 * Method to reset by reset all its fields
	 */
	@Override
	public void reset() throws Exception {
		child.reset();
		count = 0; 
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

	public void dump(String filePath) throws Exception{
        ArrayList<Tuple> sorted_tuples = organize(child, schema_pair);
       if(this.child instanceof ScanOperator){
    	   ((ScanOperator)child).close();
       }
       TupleWriter writer= new BinaryWriter(filePath);
       TupleWriter writerReadable = null;
       if (QueryPlan.debuggingMode) {writerReadable = new DirectWriter(filePath+"_humanreadable");}
        if(sorted_tuples!=null){
	    	for(Tuple tuple: sorted_tuples) {
	//    		System.out.println("write tuple" + tu.getComplete());
	    		writer.writeNext(tuple);
	    		if (QueryPlan.debuggingMode){writerReadable.writeNext(tuple);}
	    	}
        }
    	writer.close();
    	if (QueryPlan.debuggingMode){writerReadable.close();}
	}
	
	private ArrayList<Tuple> organize(Operator child, ArrayList<SchemaPair> schema_pair) throws Exception {
		ArrayList<Tuple> tupleList = new ArrayList<Tuple>();
		Tuple tu;
		while ((tu = child.getNextTuple()) != null) {
			tupleList.add(tu);
		}
		
		//create a sorting order array for comparing tuples
		if(!tupleList.isEmpty()) {setSortingIndexOrder(tupleList.get(0));}
		
		Comparator<Tuple> compare = new Comparator<Tuple>() {
			@Override
			public int compare(Tuple t1, Tuple t2) {
				
				String[] leftTuple = t1.getTuple();
				String[] rightTuple = t2.getTuple();
				
				for(int j = 0; j < leftTuple.length; j++){
					int k = orderByIndex[j];
					if(Integer.parseInt(leftTuple[k]) < Integer.parseInt(rightTuple[k])){
						return -1;
					}
					else if(Integer.parseInt(leftTuple[k]) > Integer.parseInt(rightTuple[k])){
						return 1;
					}
				}
				return 0;
				}
		};
		Collections.sort(tupleList, compare);
		return tupleList;
	}
	
	/**
	 * Create the sorting array of table for comparing tuples
	 * @param tuple the tuple contains schemaList information
	 */
	private void setSortingIndexOrder(Tuple tuple) {
		int index = 0;
		ArrayList<SchemaPair> sortingTupleSchemaList = tuple.getSchemaList();

		orderByIndex = new Integer [sortingTupleSchemaList.size()];
		for(SchemaPair schemaPair: schema_pair){
			int n = indexOfSortingTupleSchemaList(sortingTupleSchemaList,schemaPair);
			if(!Arrays.asList(orderByIndex).contains(n)) {orderByIndex[index++]=n; }
		}
		for(int i = 0; i < sortingTupleSchemaList.size(); i++){
			if(!orderByListContains(schema_pair,sortingTupleSchemaList.get(i))){
				orderByIndex[index++] = i;
			}			
		}
	}
	/**
	 * Obtain the index of this schemaPair element stored in sortingTupleSchemaList
	 * @param sortingTupleSchemaList the schemaPair list of the tuple to be sorted
	 * @param schemaPair the schemaPair to be checked
	 * @return
	 */
	private int indexOfSortingTupleSchemaList(ArrayList<SchemaPair> sortingTupleSchemaList, SchemaPair schemaPair) {
		for(int i = 0; i < sortingTupleSchemaList.size(); i++){
			if(sortingTupleSchemaList.get(i).equalsTo(schemaPair)){
				// System.out.println("index "  + i);
				return i;
			}
		}
		return -1;
	}

	/**
	 * Check if the schemaPair is one of elements in schemaPairList that contains orderby elements
	 * @param schemaPairList the list contains OrderBy elements
	 * @param schemaPair the pair to be checked
	 * @return
	 */
	private boolean orderByListContains(ArrayList<SchemaPair> schemaPairList, SchemaPair schemaPair) {
		for(SchemaPair pair: schemaPairList){
			if(pair.equalsTo(schemaPair)){
				return true;
			}
		}
		return false;
	}
	@Override
	public void setLeftChild(Operator child) throws Exception {
		this.child = child;
		
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
	}

	@Override
	public Expression getExpression() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void reset(int index) throws Exception {
		count=index;
	}
	
	@Override
	public String toString(){
		StringBuilder sb = new StringBuilder();
	
		sb.append("Sort").append(schema_pair);
		return sb.append("\n").toString();
	}
	
	@Override
	public void addChildren(Operator operator) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public ArrayList<Operator> getChildren() {
		// TODO Auto-generated method stub
		return null;
	}
}
