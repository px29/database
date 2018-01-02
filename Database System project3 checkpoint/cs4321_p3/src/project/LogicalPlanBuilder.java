package project;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import logicalOperator.*;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.SelectItem;
public class LogicalPlanBuilder {
	private QueryInterpreter queryInterpreter;
	private TreeNode root;
	private HashMap<String, Expression> JoinEx;
	private HashMap<String, Expression> SelectEx;
	private catalog cl;
	/**
	 * Constructor
	 * @param queryInter get statement elements from query interpreter
	 * @author Chengcheng Ji (cj368), Pei Xu (px29) and Ella Xue (ex32)
	 */
	public LogicalPlanBuilder(QueryInterpreter queryInter,catalog cl){
		this.queryInterpreter = queryInter;
		this.root = null;
		this.JoinEx = null;
		this.SelectEx = null;
		this.cl = cl;
		setJoinAndSelectCondition(cl);
//		if(SelectEx != null){printCondition(SelectEx);}
//		if(JoinEx != null){printCondition(JoinEx);}
	}
	
	/**
	 * @return the root operator of the query plan tree
	 */
	public TreeNode getRootOperator(){return this.root;}
	
	/**
	 * Construct a query plan with tree structure
	 * @return the root of query plan tree
	 */
	public TreeNode buildQueryPlan(){	
		
		//create first select operator and set up select condition
		TreeNode curOperator = new LogicalSelectOperator(new LogicalScanOperator());
		curOperator.setTable(queryInterpreter.getFirstTable());
		
		//get original table name or table's alias name
		String firstTableName;
		if(cl.UseAlias()){firstTableName = queryInterpreter.getFirstTable().getAlias();}
		else {firstTableName= queryInterpreter.getFirstTable().getName();}
		 
		//get selection condition if exists for the current select operator
		if(SelectEx != null && SelectEx.containsKey(firstTableName)){
			curOperator.setExpressoin(SelectEx.get(firstTableName));
		}
		
		// add join operators based on the number of join elements in the list
		List<Join> joinList = queryInterpreter.getJoinList();
		if(joinList != null){
			for(Join join: joinList){
				//set up right operator condition
				TreeNode selectOperator = new LogicalSelectOperator(new LogicalScanOperator());
				selectOperator.setTable((Table)join.getRightItem());
				
				String tableName;
				if(cl.UseAlias()){tableName = ((Table)join.getRightItem()).getAlias();}
				else {tableName= ((Table)join.getRightItem()).getName();}
				
				if(SelectEx != null && SelectEx.containsKey(tableName)){
					selectOperator.setExpressoin(SelectEx.get(tableName));
				}
				
				//create join operator of left table and right table, and set up joinOperator condition, 
				TreeNode joinOperator = new LogicalJoinOperator(curOperator,selectOperator);
				joinOperator.setTable((Table)join.getRightItem());
				if(JoinEx != null && JoinEx.containsKey(tableName)){
					joinOperator.setExpressoin(JoinEx.get(tableName));
				}
				
				//update current top level operator as the curOperator
				curOperator = joinOperator;
			} 
		}
		
		//Add projection operator if select item is not '*' (select all)
		List<SelectItem> itemList = queryInterpreter.getSelectItemList();
		if(!(itemList.get(0) instanceof AllColumns)){
			TreeNode projectOperator = new LogicalProjectOperator(curOperator, itemList);
			curOperator = projectOperator;
		}

		//add sort operator if orderElementList has element inside
		List<OrderByElement> orderElementList = queryInterpreter.getOrderByElements();
		if(orderElementList != null){
			TreeNode sortOperator = new LogicalSortOperator(curOperator, orderElementList);
			curOperator = sortOperator;
		}
		
		//create distinct operator in the query plan tree 
		if(queryInterpreter.getDistinct() != null){
			TreeNode distinctOperator;
			// if the curOperator is not an instance of SortOperator, need to create one for distinct
			if(!(curOperator instanceof LogicalSortOperator)){
				distinctOperator = new LogicalDulplicateEliminationOperator(new LogicalSortOperator(curOperator,orderElementList));
			}
			else{ //otherwise, set the curOperator as distinctOperator's child
				
				distinctOperator = new LogicalDulplicateEliminationOperator(curOperator);
			}
			curOperator = distinctOperator;
		}
		
		return root = curOperator;
	}

	/**
	 * print out the stored join condition and select condition for debugging purpose
	 * @param expressions stored expression based on table name
	 */
	private void printCondition(HashMap<String, Expression> expressions) {
		// TODO Auto-generated method stub
		for(Map.Entry<String, Expression> entry: expressions.entrySet()){
			System.out.println("table " + entry.getKey() + " with expression " + entry.getValue());
		}
	}

	/**
	 * This method evaluates each table's select condition and join condition if exists.
	 * @param cl the catalog contain table information and table's alias
	 */
	public void setJoinAndSelectCondition(catalog cl){
		Expression ex = queryInterpreter.getWhereCondition();
		Table firstTable = queryInterpreter.getFirstTable();
		List<Join> joinList = queryInterpreter.getJoinList();		
		ArrayList<String> tableList = new ArrayList<String>();
		
		if (!cl.UseAlias()) {tableList.add(firstTable.getName());} 
		else {tableList.add(firstTable.getAlias());}
		
		if (joinList != null) {
			for (Join j : joinList) {
				String name = ((Table) j.getRightItem()).getName();
				if (cl.UseAlias()) {
					name = ((Table) j.getRightItem()).getAlias();
				}
				tableList.add(name);
			}
			
		}
		processWHERE pw = new processWHERE(ex, tableList);
		JoinEx = pw.getJoinEx();
		SelectEx = pw.getSelectEx();
		if (JoinEx.isEmpty()) {JoinEx = null;}
		if (SelectEx.isEmpty()) {SelectEx = null;}
	}
}
