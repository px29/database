package physicalOperator;

import java.util.ArrayList;
import IO.BinaryWriter;
import IO.DirectWriter;
import IO.TupleWriter;
import net.sf.jsqlparser.expression.Expression;
import project.QueryPlan;
import project.SchemaPair;
import project.Tuple;
import queryPlanBuilder.PhysicalPlanBuilder;

/**
 * Join operator which uses sort merge join implementation. It only 
 * implements the merge logic.
 * @author anorakj
 *
 */
public class SMJoinOperator extends Operator {
	private Operator leftChild; // left sort operator
	private Operator rightChild; // right sort operator
	private ArrayList<SchemaPair> leftSchema;
	private ArrayList<SchemaPair> rightSchema;
	private int sortMethod = PhysicalPlanBuilder.getSortMethod();
	private Operator actualLeftChild;
	private Operator actualRightChild;
	private ArrayList<Integer> leftIndex;
	private ArrayList<Integer> rightIndex;
	private Tuple tl;
	private Tuple tr;
	private Tuple gr; 
	private int count=0;
	private int firstCall=0;

	public SMJoinOperator(Operator leftChild, Operator rightChild, ArrayList<SchemaPair> leftSchema,
			ArrayList<SchemaPair> rightSchema) throws Exception {
		this.leftChild = leftChild;
		this.rightChild = rightChild;
		this.leftSchema = leftSchema;
		this.rightSchema = rightSchema;
	}

	@Override
	public Tuple getNextTuple() throws Exception {
		if (firstCall==0) {
			tl = leftChild.getNextTuple(); 
			if(tl!=null) {leftIndex = joinIndexOrder(tl, leftSchema);}
			tr = rightChild.getNextTuple(); 
			gr=tr;
			if(tr!=null) {rightIndex = joinIndexOrder(tr, rightSchema);}
			count++;
			firstCall=1;
		}
		Tuple result=null;
		while (tl != null && tr != null) {
			while (compare(tl, gr)==-1) {
				tl=leftChild.getNextTuple();
				if(tl==null) {return null;}
			}
			while(gr!=null&&compare(tl, gr)==1) {
				gr=rightChild.getNextTuple();
				tr=gr;
				count++;
				if(gr==null) {return null;}
			}
			while(compare(tl, gr)==0) {
				while(compare(tl, tr)==0) {
					ArrayList<SchemaPair> def_schema = new ArrayList<SchemaPair>(tl.getSchemaList());
					String[] lt = tl.getTuple();
					StringBuffer sb = new StringBuffer();
					for (String s : lt) {
						sb.append(s).append(",");
					}
					StringBuffer sb_next = new StringBuffer(sb);
					ArrayList<SchemaPair> def_schema_next = new ArrayList<SchemaPair>(def_schema);
					for (SchemaPair s : new ArrayList<SchemaPair>(tr.getSchemaList())) {
						def_schema_next.add(s);
					}
					String[] rt = tr.getTuple();
					for (String s : rt) {
						sb_next.append(s).append(",");
					}
					result = new Tuple(sb_next.toString().split(","), def_schema_next);
					tr=rightChild.getNextTuple();
					if(tr==null) {
						tr=gr;
						tl=leftChild.getNextTuple();
						rightChild.reset(count);
					}
					return result;
				}
				tr=gr;
				tl=leftChild.getNextTuple();
				if(tl==null) return null;
				rightChild.reset(count);
			}
			while(gr!=tr) {
				gr=rightChild.getNextTuple();
				count++;
			}
		}
		return null;
	}

	@Override
	public void reset() throws Exception {
		// TODO Auto-generated method stub

	}

	
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
	public void setLeftChild(Operator child) throws Exception {
		this.actualLeftChild=child;
		if (sortMethod == 0) {
			this.leftChild = new SortOperator(child, leftSchema);
		} else {
			this.leftChild = new ExternalSortOperator(child, leftSchema, PhysicalPlanBuilder.getSortPageNumber());
		}
	}

	@Override
	public void setRightChild(Operator child) throws Exception {
		this.actualRightChild=child;
		if (sortMethod == 0) {
			this.rightChild = new SortOperator(child, rightSchema);
		} else {
			this.rightChild = new ExternalSortOperator(child, rightSchema, PhysicalPlanBuilder.getSortPageNumber());
		}
	}

	@Override
	public Operator getLeftChild() {
		return actualLeftChild;
	}

	@Override
	public Operator getRightChild() {
		return actualRightChild;
	}

	@Override
	public Expression getExpression() {
		// TODO Auto-generated method stub
		return null;
	}
	
	/**
	 * compare two tuples on the join attributes
	 * @param t1
	 * @param t2
	 * @return
	 */
	private int compare(Tuple t1, Tuple t2) {
		String[] leftTuple = t1.getTuple();
		String[] rightTuple = t2.getTuple();
		for(int i = 0; i < leftIndex.size(); i++){
			int k=leftIndex.get(i);
			int j= rightIndex.get(i);
			if(Integer.parseInt(leftTuple[k]) < Integer.parseInt(rightTuple[j])){
				return -1;
			}
			else if(Integer.parseInt(leftTuple[k]) > Integer.parseInt(rightTuple[j])){
				return 1;
			}
		}
		return 0;
	}

	/**
	 * get index order of the related tuple schema
	 * @param tuple
	 * @param joinSchema
	 * @return
	 */
	private ArrayList<Integer> joinIndexOrder(Tuple tuple,ArrayList<SchemaPair> joinSchema) {
		ArrayList<Integer> index = new ArrayList<Integer> ();
		for(SchemaPair schemaPair: joinSchema){
			for (int i=0;i<(tuple.getSchemaList().size());i++) {
				if(tuple.getSchemaList().get(i).equalsTo(schemaPair)) {
					index.add(i);
				}
			}
		}
		return index;
	}

	@Override
	public void reset(int index) throws Exception {
		// TODO Auto-generated method stub
	}


}
