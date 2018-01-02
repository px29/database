package queryPlanBuilder;

import java.awt.SystemTray;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import BPlusTree.IndexInfo;
import ChooseSelectionImp.Element;
import ChooseSelectionImp.ExtractColumnFromExpression;
import ChooseSelectionImp.RelationInfo;
import ChooseSelectionImp.SELECT_METHOD;
import ChooseSelectionImp.UnionFind;
import IO.BinaryReader;
import logicalOperator.*;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.Join;
import physicalOperator.*;
import project.IndexScanConditionExtration;
import project.JoinAttributesExtraction;
import project.OperationVisitor;
import project.QueryInterpreter;
import project.QueryPlan;
import project.catalog;
import project.conditionEvaluator;
import project.processWHERE;

/**
 * This class recursively builds a physical query plan with a tree structure
 * based on the passed in logical query plan tree
 * @author Chengcheng Ji (cj368), Pei Xu (px29) and Ella Xue (ex32) 
 */
public class PhysicalPlanBuilder implements OperationVisitor{
	private Operator rootOperator = null;
	private Operator curOperator = null;
	private catalog cl;
	private int joinPageSize;
	private static int sortPageSize;
	QueryInterpreter queryInterpreter;
	private String configDir;
	private static int sortMethod;
	private int joinMethod;
	private Boolean useIndex=false;
	public static int level; 
	private IndexInfo index;
	private BufferedWriter planWriter;
	private UnionFind unionFindConditions;
	/**
	 * Constructor
	 * @param cl the catalog store table information and tables' alias 
	 * @param queryInterpreter query interpreter
	 * @t hrows Exception 
	 */
	public PhysicalPlanBuilder(catalog cl,QueryInterpreter queryInterpreter, String inputDir, UnionFind unionFindConditions) throws Exception
	{
		level = 0; 
		this.cl = cl;
		this.queryInterpreter = queryInterpreter;
		this.configDir = inputDir+File.separator+"plan_builder_config.txt";
		//		setOperatorMethod();
		joinPageSize = 4;
		sortPageSize = 4;
		sortMethod = 1;
		joinMethod = 2;
		this.unionFindConditions = unionFindConditions;
		planWriter = new BufferedWriter(new FileWriter(cl.getOutputdir()+File.separator+"query"+QueryPlan.getCount()+"_physicalplan",false));
	}

	/**
	 * @return the root of the physical query plan tree
	 */
	public Operator result(){
		return this.rootOperator;
	}

	/**
	 * this visit method recursively creates new children for the current operator
	 * to form a query operator tree
	 */
	public void visit(LogicalSelectOperator node) throws Exception {
		Operator selectOperator=null;
		String tableOriginalName = node.getTable().getName();
		String tableName = getTableName(node);
		Expression exp = node.getExpressoin();
		SELECT_METHOD selectionMethod = computeSelectCost(tableOriginalName, exp);

		switch(selectionMethod){
		case FULL_SCAN:
			if(exp == null) selectOperator = new ScanOperator(tableName);
			else selectOperator = new SelectOperator(new ScanOperator(tableName),exp);
			//System.out.println("full scan opterator chosen with table name " + tableName + " exp " + exp );
			break;
		case INDEX_SCAN:
			if (exp ==null) {
				selectOperator= new IndexScanOperator(tableName,index);
				//System.out.println("index scan opterator chosen with table name " + tableName + " exp " + exp);
			}
			else {
				selectOperator=new SelectOperator(new IndexScanOperator(tableName,index), exp);
				//System.out.println("index scan and select opterator chosen with table name " + tableName + " exp " + exp);
			}
		default:break;
		}

		if(rootOperator == null){
			rootOperator = selectOperator;
		}
		else if(curOperator instanceof JoinOperator || curOperator instanceof SMJoinOperator 
				|| curOperator instanceof BNLJOperator){
			if((curOperator).getRightChild() == null){
				(curOperator).setRightChild(selectOperator);
			}
			else{
				(curOperator).setLeftChild(selectOperator);
			}
		}
		else{curOperator.setLeftChild(selectOperator);}
	}

	/**
	 * This method compute witch scanning method is better for a selection.
	 * @param tableName current relation's name
	 * @param exp the select condition for current relation
	 * @return FULL_SCAN OR INDEX_SCAN  
	 * @throws Exception THE EXPCETION
	 */
	private SELECT_METHOD computeSelectCost(String tableName, Expression exp) throws Exception {
		//		System.out.println("table name : " +tableName );
		//get relation information such as tuple #, # of attribute per tuple
		RelationInfo relation = cl.getRelation(tableName);
		int totalTupleInTheRelation = relation.getTotalTupleInRelation();
		int numOfAttribute = relation.getNumOfAttribute();
		int fullScanCost = (int)Math.ceil(totalTupleInTheRelation * numOfAttribute * 4 / QueryPlan.pageSize);
		//		System.out.println("TableName " + tableName +  " Page number "  + fullScanCost);
		if (exp == null) {return SELECT_METHOD.FULL_SCAN;}
		//get all attributes for current relation's select condition
		ExtractColumnFromExpression ext = new ExtractColumnFromExpression();
		exp.accept(ext);
		HashSet<Column> colSet = ext.getColumnResult();

		int indexCost = Integer.MAX_VALUE, pageNumber = fullScanCost;
		double reductionFactor = 0.0;

		if(colSet!=null)
			for(Column col: colSet){
				String colName = col.getColumnName();
				Element element = unionFindConditions.findElement(colName);
				//element != null means it's operator is ==, >= , <= , > or < which are qualified for index scan
				if(element != null){
					Boolean isCluster = false;
					if(cl.hasIndex(colName,tableName)){
						reductionFactor = computeReductionFactor(element, relation,colName);
						//					System.out.println("reduction factor ==++++> " + reductionFactor);
						int curIndexCost = 0;

						//compute indexScan cost if index is clustered on this attribute
						if(cl.isIndexClustered(colName, tableName)){
							//						System.out.println("page number " + pageNumber + "  reductionFacotr " + reductionFactor);
							curIndexCost = (int) Math.ceil(3 + reductionFactor*pageNumber);
							isCluster = true;
						}
						else{//compute indexScan cost if index is not clustered on this attribute
							FileInputStream inputStream = new FileInputStream(cl.getIndexDir()+File.separator+tableName+"."+colName);
							FileChannel fc =  inputStream.getChannel();
							ByteBuffer buffer = ByteBuffer.allocate(QueryPlan.pageSize);
							fc.read(buffer);
							int leafNum = buffer.getInt(4);
							curIndexCost = (int)(3 + leafNum * reductionFactor + totalTupleInTheRelation * reductionFactor);
							inputStream.close();
							fc.close();
							isCluster = false;
						}
						//update with a lower indexCost and store it's index info 
						if(curIndexCost < indexCost){
							indexCost = curIndexCost;
							// If equality exists in this element, the attribute's lowerBound == upperBound
							Integer equal = element.getEqualityConstraint() == null? null:element.getEqualityConstraint().intValue();
							Integer indexLowBound = equal == null? (element.getLowerBound() == null? null :element.getLowerBound().intValue()):equal;
							Integer indexUpBound = equal == null? (element.getUpperBound() == null? null: element.getUpperBound().intValue()): equal;
							//if no upperBound or lowerBound for this attribute, set the attribute as the relation stat value
							indexLowBound = indexLowBound == null?  relation.getMinValOfAttr(colName): indexLowBound;
							indexUpBound = indexUpBound == null?  relation.getMaxValOfAttr(colName) : indexUpBound;
							index = new IndexInfo(tableName,colName,isCluster,indexLowBound, indexUpBound);
						} 
					}
				}
			}
		//System.out.println("=========| full scan cost " + fullScanCost + " indexCost " + indexCost + " |==============");
		return fullScanCost < indexCost ? SELECT_METHOD.FULL_SCAN : SELECT_METHOD.INDEX_SCAN;
	}

	/**
	 * This method compute reduction factor for the selection condition
	 * @param element the element contains all the unionFind attribute
	 * @param relation the current table of the select operator
	 * @param colName the table's column name
	 * @return reduction factor value
	 */
	protected static double computeReductionFactor(Element element, RelationInfo relation, String colName) {
		// If equality exists in this element, the attribute's lowerBound == upperBound
		// System.out.println(element+"element");
		Integer equal = element.getEqualityConstraint() == null? null:element.getEqualityConstraint().intValue();
		Integer indexLowBound = equal == null? element.getLowerBound() == null? null:element.getLowerBound().intValue():equal;
		Integer indexUpBound = equal == null? element.getUpperBound() == null? null:element.getUpperBound().intValue() :equal;
		//System.out.println("indexlow"+indexLowBound);
		//System.out.println("indexup"+indexUpBound);

		//current relation's min - max value
		int attributeMinVal = relation.getMinValOfAttr(colName);
		int attributeMaxVal = relation.getMaxValOfAttr(colName);

		// if either lowerBound or upperBound's value is null, set it to be table's min/max value
		int min = indexLowBound != null? indexLowBound :attributeMinVal;
		int max = indexUpBound != null? indexUpBound :attributeMaxVal;
		//		System.out.println("indexLowBound " + indexLowBound);
		//		System.out.println("indexUpBound " + indexUpBound);
		//		System.out.println("attributeMinVal " + attributeMinVal);
		//		System.out.println("attributeMaxVal " + attributeMaxVal);
		//		System.out.println("min " + min);
		//		System.out.println("max " + max);
		//		System.out.println(" (max - min + 1.0) " +  (max - min + 1.0));
		//		System.out.println(" ((attributeMaxVal - attributeMinVal + 1.0) ) " +  (attributeMaxVal - attributeMinVal + 1.0) );
		//		System.out.println("Reduction factor " + (max - min + 1.0) / (attributeMaxVal - attributeMinVal + 1.0) );

		return (max - min + 1.0) / (attributeMaxVal - attributeMinVal + 1.0) ;
	}


	/**
	 * This method return a table's name in a string format
	 * @param node the logical operator that contain table information
	 * @return the table's name
	 */
	private String getTableName(TreeNode node) {
		if(cl.UseAlias()){return node.getTable().getAlias();}
		return node.getTable().getName();
	}

	public void visit(LogicalScanOperator node){}

	/**
	 * this visit method recursively creates new children for the current operator
	 * to form a query operator tree
	 */
	@Override
	public void visit(LogicalJoinOperator node) throws Exception {
		Operator joinOperator = null;
		char[] finalOrder=node.getFinalOrder().toCharArray();
		// process where condition and extract all the join condition
		Expression ex = queryInterpreter.getWhereCondition();
		Table firstTable = queryInterpreter.getFirstTable();
		List<Join> joinList = queryInterpreter.getJoinList();		
		ArrayList<String> tableList = new ArrayList<String>();

		for (char c:finalOrder) {
			int i= Integer.parseInt(c+"");
			if(i==0) {
				if (!cl.UseAlias()) {tableList.add(firstTable.getName());} 
				else {tableList.add(firstTable.getAlias());}
			}
			else{String name = ((Table) joinList.get(i-1).getRightItem()).getName();
			if (cl.UseAlias()) {
				name = ((Table) joinList.get(i-1).getRightItem()).getAlias();
			}
			tableList.add(name);}
		}
		processWHERE pw = new processWHERE(ex, tableList);
		HashMap<String, Expression> JoinEx = pw.getJoinEx();
		HashMap<String, Boolean> useSMJ= pw.getUseSMJ();
		ArrayList<logicalOperator.TreeNode> joinchild= node.getChildren();

		for (int i=finalOrder.length-1;i>0;i--) {
			Boolean SMJ= useSMJ.get(tableList.get(i));
			Expression joinex= JoinEx.get(tableList.get(i));
			if(SMJ == null || SMJ==false) {
				joinOperator= new BNLJOperator(null, null, joinex, 5);
			}
			else {
				JoinAttributesExtraction jae = new JoinAttributesExtraction
						(joinex, tableList);
				// System.out.println("joinex is " +joinex);
				joinOperator= new SMJoinOperator(null, null, jae.getLeft(), jae.getRight());
				((SMJoinOperator)joinOperator).setEx(joinex);
			}
			if(rootOperator==null) rootOperator=joinOperator;
			else {curOperator.setLeftChild(joinOperator);}
			curOperator=joinOperator;
			joinchild.get(Integer.parseInt((finalOrder[i]+""))).accept(this);
			curOperator=joinOperator;
			if(i==1) {
				joinchild.get(Integer.parseInt((finalOrder[0]+""))).accept(this);;
			}

			//System.out.println("rightchild"+curOperator.getRightChild());
		}
	}

	/**
	 * this visit method recursively creates new children for the current operator
	 * to form a query operator tree
	 */
	@Override
	public void visit(LogicalProjectOperator node) throws Exception {
		ProjectOperator projectOperator = new ProjectOperator(null,QueryPlan.schema_pair);
		if(rootOperator == null){
			rootOperator = projectOperator;
		}
		else{
			curOperator.setLeftChild(projectOperator);
		}
		curOperator = projectOperator;
		if(node.getLeftChild() != null ) {node.getLeftChild().accept(this);}
	}

	/**
	 * this visit method recursively creates new children for the current operator
	 * to form a query operator tree
	 */
	@Override
	public void visit(LogicalSortOperator node) throws Exception {
		Operator sortOperator = new ExternalSortOperator(null,QueryPlan.schema_pair_order,sortPageSize);
		//	System.out.println("external sort method chosen with sort page size " + sortPageSize);

		if(rootOperator == null){rootOperator = sortOperator;}
		else{curOperator.setLeftChild(sortOperator);}

		curOperator = sortOperator;
		if(node.getLeftChild() != null) {node.getLeftChild().accept(this);}
	}

	/**
	 * this visit method recursively creates new children for the current operator
	 * to form a query operator tree
	 */
	@Override
	public void visit(LogicalDulplicateEliminationOperator node) throws Exception {
		//call different constructor depends on if projection is needed  
		DuplicateEliminationOperator distinctOperator;
		if(queryInterpreter.getSelectItemList().get(0) instanceof AllColumns){
			distinctOperator = new DuplicateEliminationOperator(null);
		}
		else{
			distinctOperator = new DuplicateEliminationOperator(null,QueryPlan.schema_pair);
		}
		
		ExternalSortOperator sortOperator = new ExternalSortOperator(null,QueryPlan.schema_pair_order,sortPageSize);
		distinctOperator.setLeftChild(sortOperator);
		
		if(rootOperator == null){rootOperator = distinctOperator;}
		else{curOperator.setLeftChild(distinctOperator);}
		if(node.getLeftChild() instanceof LogicalSortOperator){
			curOperator = distinctOperator;
		}
		else{
			curOperator = sortOperator;
		}
		if(node.getLeftChild() != null) {node.getLeftChild().accept(this);}
	}

	/**
	 * prints out the built physical plan tree for debugging purpose in postfix order
	 * @param op the root operator
	 * @throws Exception 
	 */
	public void printPhysicalPlanTreeHelper(Operator op, int dash) throws Exception{
		if (op == null) return;

		planWriter.write(dash(dash)+op);
		//		System.out.println(op.getClass());
		//System.out.print(dash(dash)+op);

		ArrayList<Operator> operatorList = new ArrayList<>();
		operatorList.add(op.getLeftChild());
		if(op.getRightChild()!=null) {operatorList.add(op.getRightChild());}
		if(!operatorList.isEmpty() ){
			boolean left=true;
			for(Operator operator:operatorList){
				if(op instanceof SMJoinOperator) {
					if(left) {
						//System.out.print(dash(dash+1)+((SMJoinOperator)op).leftExternal());
						planWriter.write(dash(dash+1)+((SMJoinOperator)op).leftExternal());}
					else {
						//System.out.print(dash(dash+1)+((SMJoinOperator)op).rightExternal());
						planWriter.write(dash(dash+1)+((SMJoinOperator)op).rightExternal());}
					printPhysicalPlanTreeHelper(operator,dash+2);
					left=false;
				}
				else printPhysicalPlanTreeHelper(operator,dash+1);
			}
		}
		else{
			printPhysicalPlanTreeHelper(op.getLeftChild(),dash+1);
		}
	}
	/**
	 * this method prints dashes
	 * @return
	 */
	public String dash(int dash){
		StringBuilder sb = new StringBuilder();
		for(int i =0 ; i < dash; i++){
			sb.append("-");
		}
		return sb.toString();
	}
	public void printPhysicalPlanTree(Operator op) throws Exception{
		int dash = 0;
		printPhysicalPlanTreeHelper(op,dash);
		planWriter.close();
	}


	public static int getSortMethod() {
		return sortMethod;
	}

	public static int getSortPageNumber() {
		return sortPageSize;
	}

}
