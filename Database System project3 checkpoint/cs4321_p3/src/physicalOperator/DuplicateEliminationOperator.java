package physicalOperator;
import java.io.IOException;
import java.util.ArrayList;
import net.sf.jsqlparser.expression.Expression;
import project.BinaryWriter;
import project.DirectWriter;
import project.QueryPlan;
import project.SchemaPair;
import project.Tuple;
import project.TupleWriter;

/**
 * operator to support DISTINCT constraint.
 * 
 * @author Chengcheng Ji (cj368), Pei Xu (px29) and Ella Xue (ex32)
 */

public class DuplicateEliminationOperator extends Operator {
	private Operator child;
	private ArrayList<Tuple> distinctTuple = new ArrayList<Tuple>();
	ArrayList<SchemaPair> schema_pair;
	private int count;
	private boolean addDistinctSet = false;
	private boolean flag;

	/** 
	 * Assuming the tuple from child is sorted already. Simply compare
	 * the adjacent tuple and remove the duplicate tuple according to 
	 * the columns in the select clause.
	 * @param child of the operator (which must be a sort operator here)
	 * @param schema_pair the list containing <tablename,columnnmae> pair which is in the select clause
	 * @throws IOException
	 */
	public DuplicateEliminationOperator(Operator child, ArrayList<SchemaPair> schema_pair) throws IOException {
		this.child = child;
		this.schema_pair = schema_pair;
		flag = true;
	}

	/** 
	 * Constructor to deal with "select distinct *" 
	 * @param child of the operator (which must be a sort operator here)
	 * @throws IOException
	 */
	public DuplicateEliminationOperator(Operator child) throws IOException {
		this.child = child;
		flag = false;
	}
	/**
	 * Method to obtain the next tuple 
	 * 
	 * @return (Tuple) the tuple that distinct among all the tuples with respect to
	 *  the required schemas
	 */
	@Override
	public Tuple getNextTuple() throws IOException {
		//only execute once
		if(addDistinctSet== false){
			if(this.flag == true){ addOrderSetWithOrderBy();}
			else{ addOrderSetWithoutOrderBy();}
			addDistinctSet = true;
		}
		
		if ( distinctTuple.size() != count) {
			count++;
			return  distinctTuple.get(count-1);}
		else
			return null;	}

	/**
	 * Method to reset after projection by reset all its fields
	 */
	@Override
	public void reset() throws IOException {
		child.reset();
		count=0;
	}

	/**
	 * Method to dump the results of the DISTINCT
	 */
	@Override
	public void dump() throws IOException {
		Tuple tu;
		TupleWriter writer= new BinaryWriter();
	//	TupleWriter writerReadable = new DirectWriter();
		while ((tu=this.getNextTuple())!=null) {
			writer.writeNext(tu);
	//		writerReadable.writeNext(tu);
		}
		writer.close();
		//writerReadable.close();
		QueryPlan.nextQuery();
	}

	/**
	 * store distinct tuples with order by query
	 * @throws IOException IOexception
	 */
	private void addOrderSetWithOrderBy() throws IOException{
		ArrayList<Tuple> childTu = new ArrayList<Tuple>();
		Tuple tu;
		while ((tu = child.getNextTuple()) != null) {
			childTu.add(tu);
		}
		if(!childTu.isEmpty()) {Tuple firstTuple = childTu.get(0);
		for (Tuple tuple : childTu) {
			boolean repeat = false;
			if (tuple != firstTuple) {
				for (SchemaPair pair : schema_pair) {
					for (SchemaPair p : firstTuple.getSchemaList()) {
						if (pair.equalsTo(p)) {
							pair = p;
						}
					}
					int indext1 = firstTuple.getSchemaList().indexOf(pair);
					Long valuet1 = Long.parseLong(firstTuple.getTuple()[indext1]);
					for (SchemaPair p : tuple.getSchemaList()) {
						if (pair.equalsTo(p)) {
							pair = p;
						}
					}
					int indext2 = tuple.getSchemaList().indexOf(pair);
					Long valuet2 = Long.parseLong(tuple.getTuple()[indext2]);
					if (!valuet1.equals(valuet2)) {//If encounter a distinct value, break and return the tuple
						repeat = false;
						break;
					}else repeat = true;
				}
			}

			if (!repeat) {distinctTuple.add(tuple);}
			firstTuple=tuple;
		}
		}
	}
	
	/**
	 * store distinct tuples witout orderby query
	 * @throws IOException IOexception
	 */
	private void addOrderSetWithoutOrderBy() throws IOException{
		ArrayList<Tuple> childTu = new ArrayList<Tuple>();
		Tuple tu;
		while ((tu = child.getNextTuple()) != null) {
			childTu.add(tu);
		}
		if(!childTu.isEmpty()) {Tuple firstTuple = childTu.get(0);
		for (Tuple tuple : childTu) {
			boolean repeat = false;
			if (tuple != firstTuple) {
				for (SchemaPair pair : firstTuple.getSchemaList()) {
					int indext1 = firstTuple.getSchemaList().indexOf(pair);
					Long valuet1 = Long.parseLong(firstTuple.getTuple()[indext1]);
					for (SchemaPair p : tuple.getSchemaList()) {
						if (pair.equalsTo(p)) {
							pair = p;
						}
					}
					int indext2 = tuple.getSchemaList().indexOf(pair);
					Long valuet2 = Long.parseLong(tuple.getTuple()[indext2]);
					if (!(valuet1.equals( valuet2))) {//If encounter a distinct value, break and return the tuple
						repeat = false;
						break;
					}else repeat = true;
				}
			}
			if (!repeat) {distinctTuple.add(tuple);}
			firstTuple=tuple;
		}}
	}
	
	/**
	 * set current operator's child
	 */
	@Override
	public void setLeftChild(Operator child){
		this.child = child;
	}
	
	@Override
	public void setRightChild(Operator child) {}

	@Override
	public Operator getLeftChild() {return this.child;}

	@Override
	public Operator getRightChild() {return null;}

	@Override
	public Expression getExpression() {return null;}
}
