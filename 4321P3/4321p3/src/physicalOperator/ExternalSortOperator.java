package physicalOperator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;

import IO.BinaryReader;
import IO.BinaryWriter;
import IO.DirectReader;
import IO.DirectWriter;
import IO.TupleReader;
import IO.TupleWriter;
import net.sf.jsqlparser.expression.Expression;
import project.Buffer;
import project.QueryPlan;
import project.SchemaPair;
import project.Tuple;
import project.catalog;

public class ExternalSortOperator extends Operator{
	private Operator child;
	private ArrayList<SchemaPair> schema_pair;
	int count = 0;
	private Buffer buffer;
	private String tableName[];
	private int pageNumber;
	private int attributeNumber;
	private int readFileCounter = 0;
	private int sortedRun = 0;
	catalog cl = catalog.getInstance();
	private int passCount = 0;
	private String pass = "_pass";
	private int countCurRuns = 0;
	private String finalSortedFileIdentifier = null;
	private TupleReader tupleReader;
	private int orderByIndex[];
	/**
	 * Constructor 
	 * @param BPages Buffer size with B pages
	 * @throws IOException 
	 */
	public ExternalSortOperator(Operator child,ArrayList<SchemaPair> schema_pair, int pageNumber) throws IOException{
		this.child = child;
		this.schema_pair = schema_pair;
		this.pageNumber = pageNumber;		
		this.tableName = null;
		
	}

	@Override
	public Tuple getNextTuple() throws IOException {
		if(finalSortedFileIdentifier == null){
			mergeSort();
			//finalSortedFileIdentifier example: 2_pass0  (file full name: tableName2_pass0)
			finalSortedFileIdentifier = sortedRun+pass+passCount;
			if(tableName != null){
				tupleReader = QueryPlan.debuggingMode? new DirectReader(tableName, finalSortedFileIdentifier) :
				new BinaryReader(tableName, finalSortedFileIdentifier);
			}
		}
		Tuple tuple = null;
		if(tupleReader != null){
			tuple = tupleReader.readNext();
		}
		
		return tuple;
	}
	
	private void mergeSort() throws IOException {
		Tuple tuple = null;
		//pass 0 - sort B pages' tuples at a time
		while((tuple = child.getNextTuple()) != null){
//			System.out.println("add tuple " + tuple.getComplete());
			if(tableName == null){
				setTempFileName(tuple);
				setSortingIndexOrder(tuple);
			}
			
			if(buffer.isFull()){
				//current buffer is full, sort the read tuples 
				sort();
				//write the sorted tuples to a file and clear the buffer after write
				writeToFile((++sortedRun)+pass+passCount);
			}
			buffer.add(tuple);
		}
		//not full buffer pages
		if( buffer != null && !buffer.isEmpty()){
			sort();
			writeToFile((++sortedRun)+pass+passCount);
		}
		
		int prevRuns = sortedRun;
		while(prevRuns > 1){
			merge();
			//current pass run merge finished, reset for the next pass run
			if(sortedRun == 0){
				cleanPreRunFile();//delete previous run's files
				prevRuns = countCurRuns;
				sortedRun = countCurRuns;
				readFileCounter = 0;
				countCurRuns = 0;
				passCount++;
			}
		}
	}
	
	/**
	 * Create the sorting array of table for comparing tuples
	 * @param tuple the tuple contains schemaList information
	 */
	private void setSortingIndexOrder(Tuple tuple) {
		int index = 0;
		ArrayList<SchemaPair> sortingTupleSchemaList = tuple.getSchemaList();
		orderByIndex = new int[sortingTupleSchemaList.size()];
		for(SchemaPair schemaPair: schema_pair){
			orderByIndex[index++] = indexOfSortingTupleSchemaList(sortingTupleSchemaList,schemaPair);
		}
		for(int i = 0; i < sortingTupleSchemaList.size(); i++){
			if(!orderByListContains(schema_pair,sortingTupleSchemaList.get(i))){
				orderByIndex[index++] = i;
			}			
		}
	}

	/**
	 * This method merges sorted tuples in multiple files
	 * @throws IOException
	 */
	private void merge() throws IOException{
		
		ArrayList<DirectReader> directReaders = new ArrayList<DirectReader>();
		ArrayList<BinaryReader> binaryReaders =  new ArrayList<BinaryReader>();
		
		ArrayList<Tuple> tupleList = new ArrayList<Tuple>();
		int i = 0;
		while(i < pageNumber - 1 && sortedRun >= 1){
			if(QueryPlan.debuggingMode){
				directReaders.add(new DirectReader(tableName, (++readFileCounter)+pass+passCount));
				sortedRun--;
				tupleList.add(directReaders.get(i++).readNext());
			}
			else{
				binaryReaders.add(new BinaryReader(tableName, (++readFileCounter)+pass+passCount));
				sortedRun--;
				tupleList.add(binaryReaders.get(i++).readNext());
			}
		}
		
		Buffer buffer = new Buffer(pageNumber,this.attributeNumber);
		TupleWriter writer = QueryPlan.debuggingMode ? new DirectWriter(this.toString(tableName)+(++countCurRuns)+pass+(passCount+1)):
			new BinaryWriter(this.toString(tableName)+(++countCurRuns)+pass+(passCount+1));
		
		int index = 0;
		while((index = compareTuples(tupleList)) != -1){
			buffer.add(tupleList.get(index));
			if(buffer.pageIsFull()){
				//the buffer is full, write it out
				for(Tuple t: buffer){
					writer.writeNext(t);
				}
				buffer.clear();
			}
			
			Tuple tu = QueryPlan.debuggingMode ? directReaders.get(index).readNext() : binaryReaders.get(index).readNext();
			
			if(tu != null){
				//remove the written out tuple and replace it with the new read tuples
				tupleList.remove(index);
				tupleList.add(index, tu);
			}
			else{//no more tuples on this reader buffer, remove the reader and the tuple spot
				if(QueryPlan.debuggingMode) {directReaders.remove(index);}
				else{binaryReaders.remove(index);}
				tupleList.remove(index);
			}
		}
		// write out tuples saved in un-fulled buffer,
		if(!buffer.isEmpty()){
			for(Tuple t: buffer){
				writer.writeNext(t);
			}
			buffer.clear();
		}
		writer.close();
	}

	/**
	 * Compare the tuple list based on ORDERBY elements and return the index of the smallest tuple
	 * @param tupleList the tuple list to be compared
	 * @return the index of the smallest tuple
	 * @throws IOException
	 */
	private int compareTuples(ArrayList<Tuple> tupleList) throws IOException {
		if(tupleList.size() == 0) return -1;
		int smallestTupleIndex = 0;
		String[] smallestTuple = tupleList.get(0).getTuple();

		for(int i = 1; i < tupleList.size(); i++){
			String[] curTuple = tupleList.get(i).getTuple();
			for(int j = 0; j < smallestTuple.length; j++){
				int k = orderByIndex[j];
				if(Integer.parseInt(curTuple[k]) < Integer.parseInt(smallestTuple[k])){
					smallestTupleIndex = i;
					smallestTuple = curTuple;
					break;
				}
				else if(Integer.parseInt(curTuple[k]) > Integer.parseInt(smallestTuple[k])){
					break;
				}
			}
		}
		return smallestTupleIndex;
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
				return i;
			}
		}
		return -1;
	}

	/**
	 * Check if the schemaPair is one of elements in schemaPairList that contains orderby elements
	 * @param schemaPairList the list contains OrderBy elements
	 * @param schemaPair the pair to be checked
	 * @return true if contains
	 */
	private boolean orderByListContains(ArrayList<SchemaPair> schemaPairList, SchemaPair schemaPair) {
		for(SchemaPair pair: schemaPairList){
			if(pair.equalsTo(schemaPair)){
				return true;
			}
		}
		return false;
	}

	/**
	 * Write current sorted tuples to files
	 * @param fileIdetifier to identify which file for the same table
	 * @throws IOException
	 */
	private void writeToFile(String fileIdetifier) throws IOException {
		//create a file writer and set the combined table name as the file name
		TupleWriter writer = QueryPlan.debuggingMode? new DirectWriter(this.toString(tableName)+fileIdetifier)
				:new BinaryWriter(this.toString(tableName)+fileIdetifier);
		for(Tuple tuple:buffer){
//			System.out.println("write to file " + tuple.getComplete());
			writer.writeNext(tuple);
		}
		
		//empty the buffer for reading the next B pages
		buffer.clear();
		writer.close();
	}

	private void sort(){
//		System.out.println("================order by===================== ");
//		System.out.println("order by " + schema_pair);
//		for(int i = 0; i < orderByIndex.length;i++){
//			System.out.print(orderByIndex[i] + " ");
//		}
//		System.out.println();
		buffer.sort(new Comparator<Tuple>(){
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
		});
	}
	
	/**
	 * 
	 * @param tuple the tuple to be sorted which contains table names.
	 * Make the combined table names as the temp file name
	 * @throws IOException 
	 */
	private void setTempFileName(Tuple tuple) throws IOException {
		ArrayList<SchemaPair> sortingTupleSchemaList = tuple.getSchemaList();
		String str = "";
		
		this.attributeNumber = sortingTupleSchemaList.size();
		this.buffer = new Buffer(pageNumber, sortingTupleSchemaList.size());
		
		ArrayList<String> schema = new ArrayList<String>();
		StringBuilder sb = new StringBuilder();
		
		for(SchemaPair pair: sortingTupleSchemaList){
			schema.add(pair.getSchema());
			if(!str.equals(pair.getTableName())){
				sb.append(pair.getTableName()).append(",");
			}
			str = pair.getTableName();
		}
		tableName = sb.toString().split(",");
	}

	@Override
	public void reset() throws IOException {
		child.reset();
		count = 0; 
	}

	@Override
	public void dump() throws IOException {
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
	public void setLeftChild(Operator child) throws IOException {this.child = child;}

	@Override
	public void setRightChild(Operator child) {}

	@Override
	public Operator getLeftChild() {return this.child;}

	@Override
	public Operator getRightChild() {return null;}

	@Override
	public Expression getExpression() {return null;}
	
	/**
	 * Delete the previous runs' temp file
	 */
	private void cleanPreRunFile() {
		for(int i = 0; i <= readFileCounter; i++){
			File file = new File(cl.getTempFileDir()+File.separator+this.toString(tableName)+i+pass+passCount);
			file.delete();
		}
	}
	
	/**
	 * Convert array of names to a string format
	 * @param name array of names
	 * @return string of combined names
	 */
	private String toString(String name[]){
		String res = "";
		for(int i = 0; i < name.length; i++){
			res+=name[i];
		}
		return res;
	}
	
	/**
	 * 
	 * @return the external sorted file's table name
	 */
	public String getSortedTableName(){
		return this.toString(tableName);
	}
	
	/**
	 * 
	 * @return the external sorted file's identifier
	 */
	public String getSortedFileIdentifier(){
		return this.finalSortedFileIdentifier;
	}
	
	/**
	 * return the external sorted file's full name saved in temp directory
	 * @return the external sorted file's full name saved in temp directory
	 */
	public String getSortedFileFullName(){
		return this.getSortedTableName()+this.getSortedFileIdentifier();
	}

	@Override
	public void reset(int index) throws IOException {
		tupleReader.reset(index);
	}
}
