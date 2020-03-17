## 1000 Genomes processing

This document describes the full process of preparing data and *gcs-popgen* app for FASTQ->VCF processing of [1000 Genomes Project](https://www.internationalgenome.org/) human genome dataset

### CSV annotations 
Current directory contains [CSV file](reads.csv) with dataset metadata that was built from NCBI [SRA Run selector](https://www.ncbi.nlm.nih.gov/Traces/study/). List of runs was taken from 1000 Genomes Project Phase 3 [sequence index file](http://ftp.1000genomes.ebi.ac.uk/vol1/ftp/phase3/20130502.phase3.sequence.index) 

### Preparing environment
Following steps should be performed to prepare your GCP environment: 
1. Make sure you have created [Google Cloud Project](https://console.cloud.google.com) and linked it to a billing account.
Store project id into your shell session with the following command: 
    ```
    PROJECT_ID=`gcloud config get-value project`
    ```
2. Create a GCS bucket for source data:
     ```
    SRC_BUCKET_NAME=${PROJECT_ID}-src
    gsutil mb gs://${SRC_BUCKET_NAME}/
    ```
3. Create a GCS bucket for intermediate results:
    ```
    WORKING_BUCKET_NAME=${PROJECT_ID}-working
    gsutil mb gs://${WORKING_BUCKET_NAME}/
    ```
4. Set GCS uri for intermediate results:
    ```
    OUTPUT_GCS_URI=gs://${WORKING_BUCKET_NAME}/processing_output/
    ```
5. Create a [BigQuery](https://cloud.google.com/bigquery) dataset with name `BQ_DATASET`:
```bash
BQ_DATASET=popgen # customize dataset name
bq mk ${PROJECT_ID}:${BQ_DATASET}
```
### Retrieving source data
1. Source FASTQ files with runs sequences should be retrieved from [NCBI SRA archive](https://www.ncbi.nlm.nih.gov/sra). Simplest way is to use [SRA Tools](https://github.com/ncbi/sra-tools) to retrieve all `runId` from [CSV file](reads.csv).
Example of retrieving run FASTQ: 
    ```
    fastq-dump ${RUN_ID} --split-files --skip-technical 
    ```
    Also you can build [PubSub](https://cloud.google.com/pubsub) queue and parallelize FASTQ retrieving with [sra_publisher](../utilities/sra_publisher) and [sra_retriever](../utilities/sra_retriever) Python scripts
You can test this data processing with already prepared NA12878 sample data, see [Running example with NA12878](#running-example-with-na12878)
2. Reference gene DB (`.fasta` or `.fa`) should be retrieved from [Human Genome Resources at NCBI](https://www.ncbi.nlm.nih.gov/genome/guide/human/). 
Here is [link](ftp://ftp.ncbi.nlm.nih.gov/refseq/H_sapiens/annotation/GRCh38_latest/refseq_identifiers/GRCh38_latest_genomic.fna.gz) to GRCh38 latest genome reference.
Download reference from link, unzip it and store reference file name in `REFERENCE_NAME`
3. Reference gene DBs should be indexed. For indexing `.fasta` file Could be used [SAM Tools utilities](http://samtools.sourceforge.net/):
    ```
    samtools faidx ${REFERENCE_NAME}
    ```
    Store Reference index name in  `REFERENCE_INDEX_NAME`
    
### Data placement
1. [CSV file](reads.csv) with dataset metadata should be uploaded to `gs://${SRC_BUCKET_NAME}/sra_csv/`. GCS uri stored into: `CSV_URI`
2. Source FASTQ files with runs sequences should be uploaded to `SRC_BUCKET_NAME` accordingly following pattern `gs://${SRC_BUCKET_NAME}/fastq/<SRA_Study>/<SRA_Sample>/<RUN_FASTQ>` where:
 - `<SRA_Study>` - SRA study name from [CSV file](reads.csv)
 - `<SRA_Sample>` - SRA sample name from [CSV file](reads.csv)
 - `<RUN_FASTQ>` - FASTQ file to upload
3. Reference gene DB and index file should be uploaded to `gs://${SRC_BUCKET_NAME}/reference/`. GCS uris stored into: `REFERENCE_URI` and `REFERENCE_INDEX_URI`
4. JSON string with reference data should be created for running pipeline : 
```
REFERENCE_SHORT_NAME=${echo $REFERENCE_NAME| cut -f 1 -d '.'}
REFERENCE_DATA_JSON_STRING='[{"name":"$REFERENCE_SHORT_NAME", "fastaUri":"$REFERENCE_URI", "indexUri":"$REFERENCE_INDEX_URI"}]'
```

As a result, `SRC_BUCKET_NAME` should has a following structure:  
```lang-none
+ ${SRC_BUCKET_NAME}/
    + sra_csv/
        - reads.csv
    + fastq/
        + (sra_study_name_1)/
            + (sra_sample_name_1)/
                - (sra_run_1)_1.fastq
                - (sra_run_1)_2.fastq
                - (sra_run_2)_1.fastq
                - (sra_run_2)_2.fastq
                - (sra_run_3)_1.fastq
                - ...
            + ...
        +...
    + reference/
        - REFERENCE_NAME
        - REFERENCE_INDEX_NAME
```
### GCP settings
1. Select Compute Engine region, e.g. `us-central1` (depends on your GCP quota availability)
    ```
    REGION=us-central1
    ```
2. Select Compute Engine machine type. Preferably to use `n1-highmem-8` or bigger.
    ```
    MACHINE_TYPE=n1-highmem-16
    ```
3. Select maximum workers number that will be allocated for processing. The more the better, but it depends on your GCP quota availability.
    ```
    MAX_WORKERS=100
    ```
4. Select machine disk size in GB. Depends on sample sizes. Should be approx 3 times greater than maximum sample size.
    ```
    DISK_SIZE=1000
    ```
   Recommended also to set initial number of workers equals to `MAX_WORKERS` to avoid slow starting.
5. Set temp and staging location for Dataflow files:
    ```
    TEMP_LOC=gs://${WORKING_BUCKET_NAME}]/dataflow/temp/
    STAGING_LOC=gs://${WORKING_BUCKET_NAME}]/dataflow/staging/
    ```
### Running pipeline
There are several processing mode in which the pipeline could be ran:
1. FASTQ => Merged BAM and its index
2. FASTQ => VCF
3. FASTQ => BigQuery with VCF data

##### FASTQ => Merged BAM and its index
To run pipeline in `FASTQ => Merged BAM and its index` mode you should call from project root:
```bash
mvn clean package
java -cp target/gcp-popgen-0.0.3.jar \
    com.google.allenday.popgen.post_processing.PopGenPostProcessingApp \
        --project=$PROJECT_ID \
        --runner=DataflowRunner \
        --region=$REGION \
        --inputCsvUri=$CSV_URI \
        --workerMachineType=$MACHINE_TYPE \
        --maxNumWorkers=$MAX_WORKERS \
        --numWorkers=$MAX_WORKERS \
        --diskSizeGb=$DISK_SIZE \
        --stagingLocation=$STAGING_LOC \
        --tempLocation=$TEMP_LOC \
        --srcBucket=$SRC_BUCKET_NAME \
        --refDataJsonString=$REFERENCE_DATA_JSON_STRING \
        --outputGcsUri=$OUTPUT_GCS_URI \
        --withVariantCalling=False \
        --withExportVcfToBq=False
```
If there is need to process some specific samles you should add:
```
        --sraSamplesToFilter="<comma_separated_SRA_Samples>"
```
##### FASTQ => VCF
To run pipeline in this mode you should add Deep Variant settings parameters to previous command:

1. Worker region for Life Sciences main pipeline. Could be the same as `REGION` depends on your GCP quota availability):
    ```
    CONTROL_PIPELIE_WORKER_REGION=$REGION
    ```
2. Worker region for Life Sciences processing steps. Could be the same as `REGION` depends on your GCP quota availability):
    ```
    STEPS_WORKER_REGION=$REGION
    ```
3. Cores per worker for Make Examples step: preferably to use >= 8
    ```
    M_E_CORES=16
    ``` 
4. Cores per worker for Call Variants step: preferably to use >= 8
    ```
    C_V_CORES=16
    ``` 
5. Cores per worker for Postprocess Variants step: preferably to use > 8
    ```
    P_V_CORES=16
6. Memory (GB) for Make Examples step: preferably to use `P_V_CORES*5`
    ```
    M_E_MEMORY=80
    ``` 
7. Memory (GB) for Call Variants step: preferably to use `C_V_CORES*5`
    ```
    C_V_MEMORY=80
    ``` 
8. Memory (GB) for Postprocess Variants step: preferably to use `P_V_CORES*5`
    ```
    P_V_MEMORY=80
    ```     
9. Disk size (GB) for Make Examples step: preferably to use `DISK_SIZE`
    ```
    M_E_DISK=$DISK_SIZE
    ``` 
10. Disk size (GB) for Call Variants step: preferably to use `DISK_SIZE`
    ```
    C_V_DISK=$DISK_SIZE
    ``` 
11. Disk size (GB) for Postprocess Variants step: preferably to use `DISK_SIZE5`
    ```
    P_V_DISK=$DISK_SIZE
    ```     
12. Workers number for Make Examples step: preferably to use >= 8
    ```
    M_E_WORKERS=16
    ``` 
12. Workers number for Call Variants step: preferably to use >= 8
    ```
    C_V_WORKERS=16
    ``` 
12. Number of shards for Make Examples step output. Shuold be equal to `M_E_WORKERS`
    ```
    M_E_SHARDS=16
    ``` 
Here is a full command:
```bash
mvn clean package
java -cp target/gcp-popgen-0.0.3.jar \
    com.google.allenday.popgen.post_processing.PopGenPostProcessingApp \
        --project=$PROJECT_ID \
        --runner=DataflowRunner \
        --region=$REGION \
        --inputCsvUri=$CSV_URI \
        --workerMachineType=$MACHINE_TYPE \
        --maxNumWorkers=$MAX_WORKERS \
        --numWorkers=$MAX_WORKERS \
        --diskSizeGb=$DISK_SIZE \
        --stagingLocation=$STAGING_LOC \
        --tempLocation=$TEMP_LOC \
        --srcBucket=$SRC_BUCKET_NAME \
        --refDataJsonString=$REFERENCE_DATA_JSON_STRING \
        --outputGcsUri=$OUTPUT_GCS_URI \
        --controlPipelineWorkerRegion=$CONTROL_PIPELIE_WORKER_REGION \
        --stepsWorkerRegion=$STEPS_WORKER_REGION \
        --makeExamplesCoresPerWorker=$M_E_CORES \
        --makeExamplesRamPerWorker=$M_E_MEMORY \
        --makeExamplesDiskPerWorker=$M_E_DISK \
        --callVariantsCoresPerWorker=$C_V_CORES \
        --callVariantsRamPerWorker=$C_V_MEMORY \
        --callVariantsDiskPerWorker=$C_V_DISK \
        --postprocessVariantsCores=$P_V_CORES \
        --postprocessVariantsRam=$P_V_MEMORY \
        --postprocessVariantsDisk=$P_V_DISK \
        --makeExamplesWorkers=$M_E_WORKERS \
        --callVariantsWorkers=$C_V_WORKERS \
        --deepVariantShards=$M_E_SHARDS \
        --withExportVcfToBq=False
```
##### FASTQ => BigQuery with VCF data
To add export VCF files into [BigQuery](https://cloud.google.com/bigquery) following parameter should be added:
```
        --exportVcfToBq=True \
        --vcfBqDatasetAndTablePattern=$VCF_BQ_TABLE_PATH_PATTERN
```
where:
```
VCF_BQ_TABLE_PATH_PATTERN=${BQ_DATASET}.GENOMICS_VARIATIONS_%s
```
Full command:
```bash
mvn clean package
java -cp target/gcp-popgen-0.0.3.jar \
    com.google.allenday.popgen.post_processing.PopGenPostProcessingApp \
        --project=$PROJECT_ID \
        --runner=DataflowRunner \
        --region=$REGION \
        --inputCsvUri=$CSV_URI \
        --workerMachineType=$MACHINE_TYPE \
        --maxNumWorkers=$MAX_WORKERS \
        --numWorkers=$MAX_WORKERS \
        --diskSizeGb=$DISK_SIZE \
        --stagingLocation=$STAGING_LOC \
        --tempLocation=$TEMP_LOC \
        --srcBucket=$SRC_BUCKET_NAME \
        --refDataJsonString=$REFERENCE_DATA_JSON_STRING \
        --outputGcsUri=$OUTPUT_GCS_URI \
        --controlPipelineWorkerRegion=$CONTROL_PIPELIE_WORKER_REGION \
        --stepsWorkerRegion=$STEPS_WORKER_REGION \
        --makeExamplesCoresPerWorker=$M_E_CORES \
        --makeExamplesRamPerWorker=$M_E_MEMORY \
        --makeExamplesDiskPerWorker=$M_E_DISK \
        --callVariantsCoresPerWorker=$C_V_CORES \
        --callVariantsRamPerWorker=$C_V_MEMORY \
        --callVariantsDiskPerWorker=$C_V_DISK \
        --postprocessVariantsCores=$P_V_CORES \
        --postprocessVariantsRam=$P_V_MEMORY \
        --postprocessVariantsDisk=$P_V_DISK \
        --makeExamplesWorkers=$M_E_WORKERS \
        --callVariantsWorkers=$C_V_WORKERS \
        --deepVariantShards=$M_E_SHARDS \
        --exportVcfToBq=True \
        --vcfBqDatasetAndTablePattern=$VCF_BQ_TABLE_PATH_PATTERN
```
### Results
All intermediate results (.sam, .bam aligned results) will be stored in `WORKING_BUCKET_NAME` bucket. Here is files structure of results:
```
+ ${WORKING_BUCKET_NAME}/
    + processing_output/
        + <processing_date>/
            + result_aligned_bam/
                - (read_name_1)_(reference_name).bam
            + result_sorted_bam/
                - (read_name_1)_(reference_name).bam
            + result_merged_bam/
                - (sample_name_1)_(reference_name).bam
                - (sample_name_1)_(reference_name).bam.bai
            + result_dv/
                + (sample_name_1)_(reference_name)/
                    - (sample_name_1)_(reference_name).vcf
                    - (log_filed)
```
VCF results files will be stored in `gs://${WORKING_BUCKET_NAME}/processing_output/<processing_date>/result_dv` directory.

BigQuery VCF data will be stored in `${PROJECT_ID}:${BQ_DATASET}.GENOMICS_VARIATIONS_${REFERENCE_NAME}` table.

### Running example with NA12878
There is possibility to test processing with smaller dataset. For this purpose we add smaller version of [CSV file with NA12878 samle data](human-1k-demo-na12878.csv). 
Also, we prepare runs FASTQ files store in [Requester Pays GCS Bucket](https://cloud.google.com/storage/docs/using-requester-pays):
```bash
gs://human-1k-demo-na12878/fastq/
```
That's why there is no need to retrieve data from NCBI SRA archive

All steps that was described in main part of doc stay the same except step 1 and 2 from [Data placement](#data-placement) section:
1. [CSV file with NA12878 samle data](human-1k-demo-na12878.csv) with dataset metadata should be uploaded to `gs://${SRC_BUCKET_NAME}/sra_csv/`. GCS uri stored into: `CSV_URI`
2. Source FASTQ files with runs sequences should be uploaded to `SRC_BUCKET_NAME`. To do this simply copy entire `fastq` from demo bucket:
```bash
gsutil -u ${PROJECT_ID} cp -r gs://human-1k-demo-na12878/fastq/ gs://${SRC_BUCKET_NAME}/
```


