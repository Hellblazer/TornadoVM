package uk.ac.manchester.tornado.examples.bufet;

import uk.ac.manchester.tornado.api.TaskSchedule;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.annotations.Reduce;
import uk.ac.manchester.tornado.api.runtime.TornadoRuntime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class BufetTornadoBigIndex {

    //------Const definition segment------
    private static final int genes_population = 25000;          //maximum amount of Genes
    private static final int miRNA_groups = 10000;             //the amount of random miRNAs target groups
    private static final byte chunks = 4;                       //virtual batching; break data transfer to X chunks
    private static final int warming_up_iterations = 15;        //warm up iterations


    //------Methods definition segment------
    // Create a Hash table with unique instances and assign an ID
    public static HashMap<String, Integer> getUniqueInstances(ArrayList<String> inputData) {

        HashMap<String, Integer> map = new HashMap<>();
        int cnt = 0;                                            //First ID is the ZERO

        for (String inputDatum : inputData) {
            if (!map.containsKey(inputDatum)) {
                map.put(inputDatum, cnt++);                     //Associate key with unique ID value
            }
        }
        return map;
    }


    //Read the differentially expressed miRNAs provided by the user
    public static int[] getMiRNAsPhaseA(String filename, String delimiter, HashMap<String, byte[]> map, HashMap<String, ArrayList<Integer>> goCatGenesUnq) throws IOException {

        ArrayList<String> list = new ArrayList<>(InputScanner.getString(filename, delimiter));
        byte[] array = new byte[genes_population];
        int [] retArray = new int[(2 + goCatGenesUnq.size())]; //temporary store of return values
        int target_genes = 0;                                  //Count ones in array (gene_map)
        int group_found = 0;                                   //miRNA matches between the user-defined miRNA sequence and the Database of miRNAs
        int idx = 2;                                           //Avoid the first two positions for target_genes and group_found

        for (String token : list) {
            if (map.containsKey(token)) {
                group_found++;
                for (int i = 0; i < genes_population; i++) {
                    array[i] |= map.get(token)[i];
                }
            }
        }
        retArray[0] = group_found; //Position 0: First return value

        for (int i = 0; i < genes_population; i++) {
            if (array[i] == 1) {
                target_genes++;
            }
        }
        retArray[1] = target_genes; //Position 1: Second return value

        for (HashMap.Entry<String,ArrayList<Integer>> entry: goCatGenesUnq.entrySet()) {
            //how many Genes are associated to the specific goCategory
            int intersection = 0;
            for (Integer token : entry.getValue()) {
                if (array[token] == 1) {
                    intersection++;
                }
            }
            retArray[idx] = intersection;
            idx++;
        }
        return retArray;
    }

    //Read the differentially expressed miRNAs provided by the user
    //Generate the checkGO and nocheckGO data structures
    public static HashMap<String, ArrayList<String>> getMiRNAsPhaseB(HashMap<String, ArrayList<Integer>> goCatGenesUnq, HashMap<String,
            ArrayList<String>> goCategories, int[] phaseAarr, boolean goOrNogo) {

        HashMap<String, ArrayList<String>> map = new HashMap<>();
        int idx = 2;

        for (HashMap.Entry<String,ArrayList<Integer>> entry: goCatGenesUnq.entrySet()) {
            if ((phaseAarr[idx] > 0) && (goOrNogo == true)) {                           //Generate checkGO
                map.put(entry.getKey(), new ArrayList<String>());
                map.get(entry.getKey()).add(entry.getKey());                            //goCategory
                map.get(entry.getKey()).add(Integer.toString(entry.getValue().size())); //Amount of Genes for the goCategory
                map.get(entry.getKey()).add(Integer.toString(phaseAarr[idx]));          //intersection
                map.get(entry.getKey()).add(Double.toString((double)phaseAarr[idx]/(double) phaseAarr[1])); //overall_proportion
                map.get(entry.getKey()).add(Double.toString(0.0));                   //mean_overlap
                for (String token:goCategories.get(entry.getKey())) {
                    map.get(entry.getKey()).add(token);
                }
            }

            if ((phaseAarr[idx] <= 0) && (goOrNogo == false)) {                         //Generate nocheckGO
                map.put(entry.getKey(), new ArrayList<String>());
                map.get(entry.getKey()).add(entry.getKey());                            //goCategory
                map.get(entry.getKey()).add(Integer.toString(entry.getValue().size())); //Amount of Genes for the goCategory
                map.get(entry.getKey()).add(Integer.toString(0));                    //intersection
                map.get(entry.getKey()).add(Double.toString(0.0));                   //overall_proportion
                for (String token:goCategories.get(entry.getKey())) {
                    map.get(entry.getKey()).add(token);
                }
            }
            idx++;
        }
        return map;
    }


    //Generate a random index
    public static int[] generateRandom(int upperBound, int size) {

        int[] array = new int[size];

        Random rand = new Random();
        for (int i = 0; i < size ; i++)
            array[i] = rand.nextInt(upperBound);
        return array;
    }


    //Generate the miRNAs target groups
    public static void getRandomTargetGroup(byte[] onlyGeneVector, int[] randID, int[] array,
                                            final int miRNA_groups_l, int[] bounds, byte[] randNum) {

        for (@Parallel int k = 0; k < miRNA_groups_l; k++) {                    //bounds[0]
            for (int i = 0; i < bounds[2]; i++) {                               //size
                int randIdx = randID[(k * bounds[2]) + i] * bounds[1];
                int product = k * bounds[1];
                for (int j = 0; j < bounds[1]; j++) {                           //gene_population_l
                    array[(product + j)] |= onlyGeneVector[(randIdx + j)];
                }
            }
        }
    }

    //Calculate the number of genes targeted by each random miRNA group
    public static void calculateCounts(int[] map_all, int[] countOnes, int[] bounds) {

        int value = 0;

        for (@Parallel int i = 0; i < bounds[0]; i++) {                                     //miRNA_groups
            value = 0;
            for (int j = 0; j < bounds[1]; j++) {                                           //population
                value = (short) (value + (map_all[(i * bounds[1]) + j] & 1));
            }
            countOnes[i] = value;
        }
    }


    //Calculate the intersections of all random miRNA sets
    //for the candidate GO category and calculate the sets with greater overlap
    //@TODO: This method could also be re-structured for acceleration
    public static void findIntersections(int[] map_all, int[] countOnes, long[] pValues, final int miRNA_groups_l, final int genes_population_l,
                                         HashMap<String, ArrayList<String>> checkGO, HashMap<String, ArrayList<Integer>> goCatUniqueGenes) {

        int idx = 0;
        double overlap = 0.0;

        for (HashMap.Entry<String,ArrayList<String>> entry: checkGO.entrySet()) {
            double tempMeanOverlap = Double.parseDouble(entry.getValue().get(4));           //temp var for mean_overlap
            double overallProportion = (Double.parseDouble(entry.getValue().get(3)));

            for (int i = 0; i < miRNA_groups_l; i++) {
                int intersection = 0;
                for(Integer token: goCatUniqueGenes.get(entry.getKey())) {
                    if (map_all[((i * genes_population_l) + token)] == 1) {
                        intersection++;
                    }
                }

                overlap = (double)intersection / (double)countOnes[i];
                tempMeanOverlap += overlap;
                if ((overlap >= overallProportion)) {        //overall_proportion
                    pValues[idx]++;
                }
            }
            entry.getValue().set(4,Double.toString(tempMeanOverlap));
            idx++;
        }
    }


    //Write to file the output of the miRNA enrichment process
    public static void WriteOutput(String fileName, ArrayList<Double> pvalueList, ArrayList<String> fdrList,
                                   HashMap<String, ArrayList<String>> checkGO,  HashMap<String, ArrayList<String>> nocheckGO) {

        ArrayList<String> text = new ArrayList<>(); //text to write to the file
        int i = 0, j = 0;
        double mean_overlap = 0.0;

        text.add("GO-term-ID  GO-term-size  Gene-Overlap ");
        text.add("Mean-Target-Overlap-Proportion  empirical-p-value  Benjamini-Hochberg-0.05-FDR \n");
        text.add("\n");

        for (HashMap.Entry<String,ArrayList<String>> entry: checkGO.entrySet()) {
            text.add(entry.getValue().get(0));                                                      //go Category
            text.add("\t");
            text.add(entry.getValue().get(5));                                                      //name
            text.add("\t");
            text.add(entry.getValue().get(1));                                                      //size
            text.add("\t");
            text.add(entry.getValue().get(3));                                                      //overall_proportion
            text.add("\t");
            mean_overlap =  (Double.parseDouble(entry.getValue().get(4)) / (double)miRNA_groups);   //mean_overlap
            text.add(Double.toString(mean_overlap));
            text.add("\t");
            text.add(Double.toString(pvalueList.get(i)));                                           //pvalue
            text.add("\t");
            text.add(fdrList.get(i));
            text.add("\n");
            i++;
        }
        for (HashMap.Entry<String,ArrayList<String>> entry: nocheckGO.entrySet()) {
            text.add(entry.getValue().get(0));
            text.add("\t");
            text.add(entry.getValue().get(4));
            text.add("\t");
            text.add(entry.getValue().get(1));
            text.add("\t");
            text.add(entry.getValue().get(3));
            text.add("\t");
            text.add("0.0");
            text.add("\t");
            text.add(fdrList.get(i + j));
            text.add("\n");
            j++;
        }
        InputScanner.writeString(fileName,text);
    }


    public static void main(String[] args) throws IOException {

        if (args.length < 5) {
            //Command-line arguments sequence:
            //miRanda_dataset.csv, delimiter, annotation_dataset.csv, delimiter, miRNA_sequence, output_file
            System.out.println("Error: Argument(s) is(are) missing (" + args.length + " out of 5)!");
        }

        //Store interactions between miRNAs and Genes
        System.out.println("Reading " + args[0] + " and generate a Hashmap for miRNA-Gene interactions.");
        HashMap<String, ArrayList<String>> miRNA_Genes = new HashMap<>(InputScanner.readString(args[0],args[1],0,1, false));

        System.out.println("Reading " + args[2] + " and generate a Hashmap for unique Genes.");
        HashMap<String, Integer> genes = new HashMap<>(getUniqueInstances(InputScanner.getString(args[2],args[3])));

        System.out.println("Reading " + args[2] + " and generate a Hashmap for Go categories.");
        //Store only the first instance of the go Category name
        HashMap<String, ArrayList<String>> goCategories = new HashMap<>(InputScanner.readString(args[2],args[1],1,2, true));

        System.out.println("Reading " + args[2] + " and generate a Hashmap for GoGenes <goCategory, Gene, ...>.");
        HashMap<String, ArrayList<String>> goCatGenes = new HashMap<>(InputScanner.readString(args[2],args[1],1,0, true));

        //Each go Category contains a vector of Gene IDs
        HashMap<String, ArrayList<Integer>> goCatUniqueGenes = new HashMap<>();
        for (HashMap.Entry<String,ArrayList<String>> entry: goCatGenes.entrySet()) {
            goCatUniqueGenes.put(entry.getKey(), new ArrayList<Integer>());
            for (String token: entry.getValue()) {
                if (genes.containsKey(token)) {
                    goCatUniqueGenes.get(entry.getKey()).add(genes.get(token));
                }
            }
        }

        //Each miRNA is associated with a bit vector of active Genes
        HashMap<String, byte[]> interactions = new HashMap<>();
        for (HashMap.Entry<String,ArrayList<String>> entry: miRNA_Genes.entrySet()) {
            interactions.put(entry.getKey(), new byte[genes_population]);
            for (String token: entry.getValue()) {
                if (!genes.containsKey(token)) {
                    //Append the new gene to Genes' structure
                    int maxId = 0;
                    for (Map.Entry Entry : genes.entrySet()) {
                        int value = (int) Entry.getValue();
                        if (value > maxId) {
                            maxId = value;
                        }
                    }
                    genes.put(token, (maxId + 1));
                }
                (interactions.get(entry.getKey()))[genes.get(token)] = 1;
            }
        }

        //Store the miRNA target group for the user-defined miRNA sequence
        System.out.println("Generate miRNA target group for the user-defined miRNA sequence (Phase A, and B).");
        int[] getMiRNAsretVal;
        getMiRNAsretVal = getMiRNAsPhaseA(args[4],args[1],interactions, goCatUniqueGenes);

        HashMap<String, ArrayList<String>> checkGO;
        checkGO = getMiRNAsPhaseB(goCatUniqueGenes, goCategories, getMiRNAsretVal, true);

        HashMap<String, ArrayList<String>> nocheckGO;
        nocheckGO = getMiRNAsPhaseB(goCatUniqueGenes, goCategories, getMiRNAsretVal, false);

        //prepareRandom() --> Not implemented (performs a sanity check)

        //Cast Hash table to an array of byte values
        byte[] onlyGeneVector = new byte[genes_population * interactions.size()];
        int j = 0;                                                          //aux index to parse array
        for (HashMap.Entry<String,byte[]> entry: interactions.entrySet()) { //interactions.size() iterations
            int i = 0;
            for (byte token: entry.getValue()) {                            //gene_population iterations
                //A new miRNA target group is stored every genes_population byte values
                onlyGeneVector[(j * genes_population) + i] = token;
                i++;
            }
            j++;
        }

        System.out.println("Generate random IDs");
        int[] randID = generateRandom(miRNA_Genes.size(), (getMiRNAsretVal[0] * miRNA_groups));
        int chunkElements = miRNA_groups / chunks;
        int checkGoSize = checkGO.size();                                                   //get the number of unique categories
        int[] countOnes = new int[chunkElements];                                       //store the number of Ones per miRNA group
        long[] pValues = new long[checkGoSize];
        byte[] randNum = new byte[] {0,0};                                                  //aux array to block unrolling in getRandomTargetGroup method
        int[] bounds = new int[] {chunkElements, genes_population, getMiRNAsretVal[0]};     //aux array to block unrolling in getRandomTargetGroup method
        int[] map_all_split = new int[chunkElements * genes_population];

        //Sanity check #1
        if ((chunkElements * genes_population) >= Integer.MAX_VALUE) {
            System.out.println("Number of array elements is too big. Update the amount of chunks.");
            System.exit(0);
        }

        //Sanity Check #2
        double maxDeviceMemory = (TornadoRuntime.getTornadoRuntime().getDriver(0).getDefaultDevice().getMaxAllocMemory());
        if (((chunkElements * genes_population) * 4) >= maxDeviceMemory) {
            System.out.println("Maximum alloc device memory: " + maxDeviceMemory  * 1E-6 + " (MB) for default device. Either update device or the amount of chunks.");
            System.exit(0);
        }

        for (int i = 0; i < chunks; i++) {

            System.out.println("Generate the miRNAs target groups for chunk " + i);
            TaskSchedule s0 = new TaskSchedule("x" + i);
            s0.task("t0", BufetTornadoBigIndex::getRandomTargetGroup, onlyGeneVector, randID, map_all_split,
                    chunkElements, bounds, randNum);
            s0.streamOut(map_all_split);

            System.out.println("Count ones in each miRNA target group.");
            s0.task("t1", BufetTornadoBigIndex::calculateCounts, map_all_split, countOnes, bounds);
            s0.streamOut(countOnes);

            for (int z = 0; z < warming_up_iterations; z++) {
                long start = System.nanoTime();
                s0.execute();
                long stop = System.nanoTime();
                System.out.println("Total Time X: " + (stop - start) + " (ns)");
            }

            System.out.println("Calculate the intersections for all random miRNA sets");
            findIntersections(map_all_split, countOnes, pValues, chunkElements, genes_population, checkGO, goCatUniqueGenes);
        }

        System.out.println("Calculate the BH FDR correction.");
        BenjaminHochberg bh = new BenjaminHochberg();
        bh.benjaminHochberg(checkGO, nocheckGO, pValues, miRNA_groups);

        ArrayList<Double> pValueList;
        ArrayList<String> fdrList;
        pValueList = bh.returnPvalueList();
        fdrList = bh.returnFdrList();

        System.out.println("Write output to file " + args[5] + ".");
        WriteOutput(args[5], pValueList, fdrList, checkGO, nocheckGO);

        System.out.println("Bufet algorithm has finished.");
    }

}
