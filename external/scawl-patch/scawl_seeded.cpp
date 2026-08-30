#include <iostream>
#include <fstream>
#include <sstream>
#include <numeric>
#include <algorithm>
#include <assert.h>
#include <tuple>
#include <vector>
#include <set>
#include <unordered_map>
#include <../parallel_hashmap/phmap.h>
#include <cmath>
#include <thread>
#include <time.h>
#include "../util/mm_helper.hpp"
#include <cstring>
#include <omp.h>
#include "dialysis_mpi_stub.h"
#include <chrono>
#include <atomic>
#include <deque>
#include <unistd.h>
#include <dirent.h> // POSIX library for directory operations

using namespace std;
using namespace phmap;

#define SWAP_PTRS(currentColors, nextColors) \
    {                                        \
        int *temp = currentColors;           \
        currentColors = nextColors;          \
        nextColors = temp;                   \
    }

// Global pointer for ofstream
std::ofstream* pOutputFile = nullptr;

#ifdef USE_FILE_OUTPUT
    #define OUT (*pOutputFile)
#else
    #define OUT std::cout
#endif

struct Tuple
{
    int x0;
    int x1;
};

template <typename T>
struct SortPair
{
    int originalLocation;
    T valueToSort;
    bool operator<(const SortPair<T>& other) const {
        return valueToSort < other.valueToSort;
    }

    bool operator>(const SortPair<T>& other) const {
        
        return valueToSort > other.valueToSort;
    }

    bool operator==(const SortPair<T>& other) const {
        return valueToSort == other.valueToSort;
    }
};

template <typename T>
SortPair<T>* getSortPairs(T* valuesToSort, int* originalLocations, int size)
{
    SortPair<T>* pairs = new SortPair<T>[size];
    for(int i = 0; i < size; i++)
    {
        pairs[i].valueToSort = valuesToSort[i];
        pairs[i].originalLocation = originalLocations[i];
    }
    return pairs;
}

template <typename T>
void loadFromSortPairs(SortPair<T>* pairs, T* valuesToSort, int* originalLocations, int size)
{
    for(int i = 0; i < size; i++)
    {
        valuesToSort[i] = pairs[i].valueToSort;
        originalLocations[i] = pairs[i].originalLocation;
    }
}

template <typename T>
void cleanupSortPairs(SortPair<T>* values)
{
    delete [] values;
}

template <typename T>
void sortValueAndLocationPair(T* values, int* originalLocations, int size)
{
    SortPair<T>* sortPairsToSort = getSortPairs(values, originalLocations, size);
    quicksort(sortPairsToSort, 0, size-1);
    loadFromSortPairs(sortPairsToSort, values, originalLocations, size);
    cleanupSortPairs(sortPairsToSort);
}

struct HashResult
{
    bool matchingGraphs = false;
    int maxColor = 0;
};

struct SendRecvRoleResult
{
    int maxRank = -1;
    int minRank = -1;
    long minLoad = 999999999999999;
    long maxLoad = -1;
    long maxImbalance = -1;
    long meanAbsDeviation = 0;
    long targetLoad = 0;
};

double getTimeDiff(std::chrono::_V2::system_clock::time_point t1, std::chrono::_V2::system_clock::time_point t2)
{
    return std::chrono::nanoseconds(t2 - t1).count() / 1000000000.0;
}

COO read_matrix_market_to_COO(const char *fname)
{
    COO mat;
    std::ifstream f(fname);

    while (f.peek() == '%')
    {
        f.ignore(2048, '\n');
    }

    f >> mat.nrows >> mat.ncols >> mat.nnz;

    mat.row_id = (unsigned int *)malloc(mat.nnz * sizeof(unsigned int));
    mat.col_id = (unsigned int *)malloc(mat.nnz * sizeof(unsigned int));
    mat.values = (double *)malloc(mat.nnz * sizeof(double));

    for (unsigned int i = 0; i < mat.nnz; i++)
    {
        unsigned int m;
        unsigned int n;
        double val;
        f >> m >> n >> val;

        mat.row_id[i] = --m;
        mat.col_id[i] = --n;
        mat.values[i] = val;
    }

    return mat;
}

void read_matrix_market_size(const char *fname, int& nrows, int& ncols)
{
    std::ifstream f(fname);

    while (f.peek() == '%')
    {
        f.ignore(2048, '\n');
    }
    int rows, cols, nnz;
    f >> rows >> cols >> nnz;
    nrows = rows;
    ncols = cols;
    return;
}

void read_matrix_market_into_counts_and_xor(const char *fname, long** colCounts, long** rowCounts, long** colXor, long** rowXor, int& vertexCount)
{
    COO mat;
    std::ifstream f(fname);

    while (f.peek() == '%')
    {
        f.ignore(2048, '\n');
    }

    f >> mat.nrows >> mat.ncols >> mat.nnz;
    vertexCount = mat.nrows;
    for (unsigned int i = 0; i < mat.nnz; i++)
    {
        unsigned int m;
        unsigned int n;
        double val;
        f >> m >> n >> val;
        
        m--;
        n--;
        (*rowCounts)[m]++;
        (*colCounts)[n]++;
        (*rowXor)[m] ^= 1;
        (*colXor)[n] ^= 1;
    }
    return;
}

void read_matrix_market_into_partition(const char *fname, std::vector<int>& rowsVec, std::vector<int>& colsVec, int* rows, int* cols)
{
    COO mat;
    std::ifstream f(fname);
    std::set<int> rowsSet(rowsVec.begin(), rowsVec.end());
    std::set<int> colsSet(colsVec.begin(), colsVec.end());
    while (f.peek() == '%')
    {
        f.ignore(2048, '\n');
    }

    f >> mat.nrows >> mat.ncols >> mat.nnz;
    int vertexCount = mat.nrows;
    std::memset(rows, 0, rowsVec.size() * vertexCount * sizeof(int));
    std::memset(cols, 0, colsVec.size() * vertexCount * sizeof(int));
    for (unsigned int i = 0; i < mat.nnz; i++)
    {
        unsigned int row;
        unsigned int col;
        double val;
        f >> row >> col >> val;
        --row;
        --col;
        if(rowsSet.find(row) != rowsSet.end())
        {
            auto it = std::lower_bound(rowsVec.begin(), rowsVec.end(), row);
            int index = std::distance(rowsVec.begin(), it);
            rows[index * vertexCount + col] = 1;
        }
        if(colsSet.find(col) != colsSet.end())
        {
            auto it = std::lower_bound(colsVec.begin(), colsVec.end(), col);
            int index = std::distance(colsVec.begin(), it);
            cols[index * vertexCount + row] = 1;
        }
    }
    return;
}

CSR read_matrix_market_to_CSR(const char *fname)
{
    COO coo_mat = read_matrix_market_to_COO(fname);
    unsigned int *idx_arr = (unsigned int *)malloc(coo_mat.nnz * sizeof(unsigned int));

    std::iota(idx_arr, idx_arr + coo_mat.nnz, 0);
    std::sort(idx_arr, idx_arr + coo_mat.nnz, [&coo_mat](unsigned int i, unsigned int j)
              {
        if(coo_mat.row_id[i] < coo_mat.row_id[j])
            return true;
        else if(coo_mat.row_id[i] > coo_mat.row_id[j])
            return false;
        else if(coo_mat.col_id[i] < coo_mat.col_id[j])
            return true;
        else
            return false; });

    CSR csr_mat;
    csr_mat.nnz = coo_mat.nnz;
    csr_mat.nrows = coo_mat.nrows;
    csr_mat.ncols = coo_mat.ncols;

    csr_mat.row_indx = (unsigned int *)malloc((csr_mat.nrows + 1) * sizeof(unsigned int));
    csr_mat.col_id = (unsigned int *)malloc(csr_mat.nnz * sizeof(unsigned int));
    csr_mat.values = (double *)malloc(csr_mat.nnz * sizeof(double));

    unsigned int prev_row = 0;
    int cnt = 0;
    csr_mat.row_indx[0] = 0;

    for (unsigned int i = 0; i < csr_mat.nnz; ++i)
    {
        auto cur_idx = idx_arr[i];
        auto cur_row = coo_mat.row_id[cur_idx];
        assert(prev_row <= cur_row);
        while (prev_row != cur_row)
        {
            csr_mat.row_indx[prev_row + 1] = csr_mat.row_indx[prev_row] + cnt;
            cnt = 0;
            prev_row++;
        }
        cnt++;

        csr_mat.col_id[i] = coo_mat.col_id[cur_idx];
        csr_mat.values[i] = coo_mat.values[cur_idx];
    }
    while (prev_row < csr_mat.nrows)
    {
        csr_mat.row_indx[prev_row + 1] = csr_mat.row_indx[prev_row] + cnt;
        cnt = 0;
        prev_row++;
    }

    free(coo_mat.row_id);
    free(coo_mat.col_id);
    free(coo_mat.values);
    free(idx_arr);

    return csr_mat;
}

double *getDenseMat(CSR mat, int *nrow, int *ncol)
{
    double *denseMat = (double *)malloc(mat.nrows * mat.ncols * sizeof(double));
    for (unsigned int m = 0; m < mat.nrows; m++)
    {
        for (unsigned int n = 0; n < mat.ncols; n++)
        {
            denseMat[m * mat.ncols + n] = 0.0;
        }
    }
    for (unsigned int r = 0; r < mat.nrows; r++)
    {
        int startIndex = mat.row_indx[r];
        int endIndex = mat.row_indx[r + 1];
        for (unsigned int j = startIndex; j < endIndex; j++)
        {
            int colid = mat.col_id[j];
            double val = mat.values[j];
            denseMat[r * mat.ncols + colid] = val;
        }
    }
    (*nrow) = mat.nrows;
    (*ncol) = mat.ncols;
    return denseMat;
}

int *getDenseMatDoubleToInt(double *mat, int nrow, int ncol)
{
    int *denseMat = (int *)malloc(nrow * ncol * sizeof(int));
    for (unsigned int m = 0; m < nrow; m++)
    {
        for (unsigned int n = 0; n < ncol; n++)
        {
            if (fabs(mat[m * ncol + n]) > 0.0)
            {
                denseMat[m * ncol + n] = 1;
            }
            else
            {
                denseMat[m * ncol + n] = -1;
            }
        }
    }
    return denseMat;
}


int *Graph(const char *filename, int *nrow, int *ncol)
{
    CSR graph = read_matrix_market_to_CSR(filename);
    double *mat = getDenseMat(graph, nrow, ncol);
    int *intMat = getDenseMatDoubleToInt(mat, *nrow, *ncol);
    free(mat);
    free(graph.col_id);
    free(graph.row_indx);
    free(graph.values);
    return intMat;
}

struct ColorCount
{
    uint color;
    int count = std::numeric_limits<int>::min();
};

class GraphData
{

public:
    GraphData()
    {
        allRowColorsLoc = NULL;
        allRowColors = NULL;
        allColColorsLoc = NULL;
        allColColors = NULL;

        allRowColors1 = NULL;
        allRowColors2 = NULL;
        allColColors1 = NULL;
        allColColors2 = NULL;

        rowColors1 = NULL;
        rowColorsLoc1 = NULL;
        colColors1 = NULL;
        colColorsLoc1 = NULL;

        rowColors2 = NULL;
        rowColorsLoc2 = NULL;
        colColors2 = NULL;
        colColorsLoc2 = NULL;

        colors1CurrentRows = NULL;
        colors1CurrentCols = NULL;
        colors2CurrentRows = NULL;
        colors2CurrentCols = NULL;

        colors1SortedCurrentRows = NULL;
        colors1SortedCurrentCols = NULL;
        colors2SortedCurrentRows = NULL;
        colors2SortedCurrentCols = NULL;

        colors1NextRows = NULL;
        colors1NextCols = NULL;
        colors2NextRows = NULL;
        colors2NextCols = NULL;

        colors1NextRowsColorCountPtrs = NULL;
        colors1NextColsColorCountPtrs = NULL;
        colors2NextRowsColorCountPtrs = NULL;
        colors2NextColsColorCountPtrs = NULL;
    }
    ~GraphData()
    {
        if (colors1SortedCurrentRows != NULL)
        {
            delete[] colors1SortedCurrentRows;
        }
        if (colors1SortedCurrentCols != NULL)
        {
            delete[] colors1SortedCurrentCols;
        }
        if (colors2SortedCurrentRows != NULL)
        {
            delete[] colors2SortedCurrentRows;
        }
        if (colors2SortedCurrentCols != NULL)
        {
            delete[] colors2SortedCurrentCols;
        }
        if (rowColors1 != NULL)
        {
            delete[] rowColors1;
        }
        if (rowColorsLoc1 != NULL)
        {
            delete[] rowColorsLoc1;
        }
        if (colColors1 != NULL)
        {
            delete[] colColors1;
        }
        if (colColorsLoc1 != NULL)
        {
            delete[] colColorsLoc1;
        }

        if (rowColors2 != NULL)
        {
            delete[] rowColors2;
        }
        if (rowColorsLoc2 != NULL)
        {
            delete[] rowColorsLoc2;
        }
        if (colColors2 != NULL)
        {
            delete[] colColors2;
        }
        if (colColorsLoc2 != NULL)
        {
            delete[] colColorsLoc2;
        }
        if (colors1CurrentRows != NULL)
        {
            delete[] colors1CurrentRows;
        }
        if (colors1CurrentCols != NULL)
        {
            delete[] colors1CurrentCols;
        }
        if (colors2CurrentRows != NULL)
        {
            delete[] colors2CurrentRows;
        }
        if (colors2CurrentCols != NULL)
        {
            delete[] colors2CurrentCols;
        }
        if (colors1NextRows != NULL)
        {
            delete[] colors1NextRows;
        }
        if (colors1NextCols != NULL)
        {
            delete[] colors1NextCols;
        }
        if (colors2NextRows != NULL)
        {
            delete[] colors2NextRows;
        }
        if (colors2NextCols != NULL)
        {
            delete[] colors2NextCols;
        }

        if(allRowColorsLoc != NULL)
        {
            delete [] allRowColorsLoc;
            allRowColorsLoc = NULL;
        }
        if(allRowColors != NULL)
        {
            delete [] allRowColors;
            allRowColors = NULL;
        }
        if(allColColorsLoc != NULL)
        {
            delete [] allColColorsLoc;
            allColColorsLoc = NULL;
        }
        if(allColColors != NULL)
        {
            delete [] allColColors;
            allColColors = NULL;
        }
        if(allRowColors1 != NULL)
        {
            delete [] allRowColors1;
            allRowColors1 = NULL;
        }
        if(allRowColors2 != NULL)
        {
            delete [] allRowColors2;
            allRowColors2 = NULL;
        }
        if(allColColors1 != NULL)
        {
            delete [] allColColors1;
            allColColors1 = NULL;
        }
        if(allColColors2 != NULL)
        {
            delete [] allColColors2;
            allColColors2 = NULL;
        }
        if(colors1NextRowsColorCountPtrs != NULL)
        {
            delete [] colors1NextRowsColorCountPtrs;
            colors1NextRowsColorCountPtrs = NULL;
        }
        if(colors1NextColsColorCountPtrs != NULL)
        {
            delete [] colors1NextColsColorCountPtrs;
            colors1NextColsColorCountPtrs = NULL;
        }
        if(colors2NextRowsColorCountPtrs != NULL)
        {
            delete [] colors2NextRowsColorCountPtrs;
            colors2NextRowsColorCountPtrs = NULL;
        }
        if(colors2NextColsColorCountPtrs != NULL)
        {
            delete [] colors2NextColsColorCountPtrs;
            colors2NextColsColorCountPtrs = NULL;
        }
    }

    int TotalGraphSize()
    {
        return rows1.size() + rows2.size() + cols1.size() + cols2.size();
    }

    std::vector<int> rows1;
    std::vector<int> cols1;
    std::vector<int> rows2;
    std::vector<int> cols2;

    int *allRowColorsLoc;
    int *allRowColors;
    int *allColColorsLoc;
    int *allColColors;

    int *allRowColors1;
    int *allRowColors2;
    int *allColColors1;
    int *allColColors2;

    int *rowColors1;
    int *rowColorsLoc1;
    int *colColors1;
    int *colColorsLoc1;

    int *rowColors2;
    int *rowColorsLoc2;
    int *colColors2;
    int *colColorsLoc2;

    int *colors1CurrentRows;
    int *colors1CurrentCols;
    int *colors2CurrentRows;
    int *colors2CurrentCols;

    int *colors1SortedCurrentRows;
    int *colors1SortedCurrentCols;
    int *colors2SortedCurrentRows;
    int *colors2SortedCurrentCols;

    int *colors1NextRows;
    int *colors1NextCols;
    int *colors2NextRows;
    int *colors2NextCols;

    ColorCount** colors1NextRowsColorCountPtrs;
    ColorCount** colors1NextColsColorCountPtrs;
    ColorCount** colors2NextRowsColorCountPtrs;
    ColorCount** colors2NextColsColorCountPtrs;
};

struct InternodeBalanceResult
{
    GraphData* newGraph = NULL;
    long minMaxDelta = 0;
    long averageAbsDeviation = 0;
    long minLoad = 9999999999;
    long maxLoad = -1;  
    long targetLoad = 0;  
};

struct HashMapResult
{
    int prevColor;
    int columnColor;
    int rowColor;
    bool matchingSoFar = true;
    bool preexisting = false;
    ColorCount* colorInfo;
    int color;
};



HashMapResult GetColor(int *signature,
                       parallel_flat_hash_map<std::string, ColorCount*, std::hash<string>,
                                              std::equal_to<string>,
                                              std::allocator<std::pair<const string, ColorCount*>>,
                                              4,
                                              std::mutex> *colorMap,
                       std::atomic<int>* atomics, bool isGraph1, bool incAndDec)
{
    bool not_matching = false;
    string signatureStr = to_string(signature[0]);
    signatureStr = signatureStr + "*";
    signatureStr += to_string(signature[1]) + "*";
    signatureStr += to_string(signature[2]) + "*";
    HashMapResult result;
    ColorCount* colorInfo;
    colorMap->lazy_emplace_l(
        signatureStr, [&](parallel_flat_hash_map<std::string, ColorCount*, std::hash<string>, std::equal_to<string>, std::allocator<std::pair<const string, ColorCount*>>, 4, std::mutex>::value_type &v)
        {
            if(isGraph1 && incAndDec)
            {
                v.second->count++;
            }
            else if(incAndDec)
            {
                v.second->count--;
            }
            if(v.second->count < 0)
            {
                result.matchingSoFar = false;
            }
            colorInfo = v.second; 
            result.preexisting = true; 
        },
        [&](const parallel_flat_hash_map<std::string, ColorCount*, std::hash<string>,
                                            std::equal_to<string>,
                                            std::allocator<std::pair<const string, ColorCount*>>,
                                            4,
                                            std::mutex>::constructor &ctor)
        {
            ColorCount* val = new ColorCount();
            colorInfo = val;
            val->color = atomics->fetch_add(1);
            if(isGraph1 && incAndDec)
            {
                val->count = 1;
            }
            else if(incAndDec)
            {
                val->count = -1;
            }
            ctor(signatureStr, val);
            result.preexisting = false;
        });
    result.colorInfo = colorInfo;
    result.prevColor = signature[0];
    result.columnColor = signature[1];
    result.rowColor = signature[2];
    return result;
}

HashMapResult GetColor(int *signature,
                       parallel_flat_hash_map<std::string, int, std::hash<string>,
                                              std::equal_to<string>,
                                              std::allocator<std::pair<const string, int>>,
                                              4,
                                              std::mutex> *colorMap,
                       std::atomic<int>& atomics)
{
    string signatureStr = to_string(signature[0]);
    signatureStr = signatureStr + "*";
    signatureStr += to_string(signature[1]) + "*";
    signatureStr += to_string(signature[2]) + "*";
    HashMapResult result;
    int color;
    colorMap->lazy_emplace_l(
        signatureStr, [&](parallel_flat_hash_map<std::string, int, std::hash<string>, std::equal_to<string>, std::allocator<std::pair<const string, int>>, 4, std::mutex>::value_type &v)
        { color = v.second; result.preexisting = true; },
        [&](const parallel_flat_hash_map<std::string, int, std::hash<string>,
                                            std::equal_to<string>,
                                            std::allocator<std::pair<const string, int>>,
                                            4,
                                            std::mutex>::constructor &ctor)
        {
            color = atomics.fetch_add(1);
            ctor(signatureStr, color);
            result.preexisting = false;
        });
    result.color = color;
    result.prevColor = signature[0];
    result.columnColor = signature[1];
    result.rowColor = signature[2];
    return result;
}

void InitializeGraphMemory(GraphData* graphData, int vertexCount)
{
    if(graphData->rows1.size() + graphData->rows2.size() > 0)
    {
        graphData->allRowColorsLoc = new int[graphData->rows1.size() + graphData->rows2.size()];
        graphData->allRowColors = new int[graphData->rows1.size() + graphData->rows2.size()];
    }
    if(graphData->cols1.size() + graphData->cols2.size() > 0)
    {
        graphData->allColColorsLoc = new int[graphData->cols1.size() + graphData->cols2.size()];
        graphData->allColColors = new int[graphData->cols1.size() + graphData->cols2.size()];
    }
    graphData->allRowColors1 = new int[vertexCount];
    graphData->allRowColors2 = new int[vertexCount];
    graphData->allColColors1 = new int[vertexCount];
    graphData->allColColors2 = new int[vertexCount];
    if (graphData->cols1.size() > 0)
    {
        graphData->colColorsLoc1 = new int[graphData->cols1.size()];
        graphData->colColors1 = new int[graphData->cols1.size()];
        graphData->colors1CurrentCols = new int[graphData->cols1.size() * ((long)vertexCount)];
        graphData->colors1SortedCurrentCols = new int[graphData->cols1.size() * ((long)vertexCount)];
        graphData->colors1NextCols = new int[graphData->cols1.size() * ((long)vertexCount)];
        graphData->colors1NextColsColorCountPtrs = new ColorCount*[graphData->cols1.size() * ((long)vertexCount)]; 
    }
    if (graphData->rows1.size() > 0)
    {
        graphData->colors1CurrentRows = new int[graphData->rows1.size() * ((long)vertexCount)];
        graphData->colors1SortedCurrentRows = new int[graphData->rows1.size() * ((long)vertexCount)];
        graphData->colors1NextRows = new int[graphData->rows1.size() * ((long)vertexCount)];
        graphData->colors1NextRowsColorCountPtrs = new ColorCount*[graphData->rows1.size() * ((long)vertexCount)];
        graphData->rowColorsLoc1 = new int[graphData->rows1.size()];
        graphData->rowColors1 = new int[graphData->rows1.size()];
        
    }
    if (graphData->cols2.size() > 0)
    {
        graphData->colors2CurrentCols = new int[graphData->cols2.size() * ((long)vertexCount)];
        graphData->colors2SortedCurrentCols = new int[graphData->cols2.size() * ((long)vertexCount)];
        graphData->colors2NextCols = new int[graphData->cols2.size() * ((long)vertexCount)];
        graphData->colors2NextColsColorCountPtrs = new ColorCount*[graphData->cols2.size() * ((long)vertexCount)];
        graphData->colColorsLoc2 = new int[graphData->cols2.size()];
        graphData->colColors2 = new int[graphData->cols2.size()];
        
    }
    if (graphData->rows2.size() > 0)
    {
        graphData->colors2CurrentRows = new int[graphData->rows2.size() * ((long)vertexCount)];
        graphData->colors2SortedCurrentRows = new int[graphData->rows2.size() * ((long)vertexCount)];
        graphData->colors2NextRows = new int[graphData->rows2.size() * ((long)vertexCount)];
        graphData->colors2NextRowsColorCountPtrs = new ColorCount*[graphData->rows2.size() * ((long)vertexCount)];
        graphData->rowColorsLoc2 = new int[graphData->rows2.size()];
        graphData->rowColors2 = new int[graphData->rows2.size()];
    }
}

GraphData *initializeFromDense(int *graph1, int *graph2, int vertexCount, int rank, int worldSize)
{
    long *rowCounts1 = new long[vertexCount];
    long *colCounts1 = new long[vertexCount];
    long *rowCounts2 = new long[vertexCount];
    long *colCounts2 = new long[vertexCount];
    long *rowXor1 = new long[vertexCount];
    long *colXor1 = new long[vertexCount];
    long *rowXor2 = new long[vertexCount];
    long *colXor2 = new long[vertexCount];
    for (int k = 0; k < vertexCount; k++)
    {
        rowCounts1[k] = 0;
        colCounts1[k] = 0;
        rowCounts2[k] = 0;
        colCounts2[k] = 0;
        rowXor1[k] = 0;
        colXor1[k] = 0;
        rowXor2[k] = 0;
        colXor2[k] = 0;
    }

    GraphData *graphData = new GraphData();
    int *colors1 = new int[vertexCount * vertexCount];
    int *colors2 = new int[vertexCount * vertexCount];

    for (int i = 0; i < vertexCount; i++)
    {
        for (int j = 0; j < vertexCount; j++)
        {
            if (graph1[i * vertexCount + j] == 1)
            {
                colors1[i * vertexCount + j] = 1;
                rowCounts1[i]++;
                colCounts1[j]++;
                rowXor1[i] ^= 1;
                colXor1[j] ^= 1;
            }

            else if (graph1[i * vertexCount + j] == -1)
            {
                colors1[i * vertexCount + j] = 0;
            }

            if (graph2[i * vertexCount + j] == 1)
            {
                rowCounts2[i]++;
                colCounts2[j]++;
                rowXor2[i] ^= 1;
                colXor2[j] ^= 1;
                colors2[i * vertexCount + j] = 1;
            }
            else if (graph2[i * vertexCount + j] == -1)
            {
                colors2[i * vertexCount + j] = 0;
            }
        }
    }

    for (int p = 0; p < vertexCount; p++)
    {
        if (((rowCounts1[p] & 0xAAAAAAAAAAAAAAAA) | (rowXor1[p] & 0x5555555555555555)) % worldSize == rank)
        {
            graphData->rows1.push_back(p);
        }
        if (((colCounts1[p] & 0xAAAAAAAAAAAAAAAA) | (colXor1[p] & 0x5555555555555555)) % worldSize == rank)
        {
            graphData->cols1.push_back(p);
        }
        if (((rowCounts2[p] & 0xAAAAAAAAAAAAAAAA) | (rowXor2[p] & 0x5555555555555555)) % worldSize == rank)
        {
            graphData->rows2.push_back(p);
        }
        if (((colCounts2[p] & 0xAAAAAAAAAAAAAAAA) | (colXor2[p] & 0x5555555555555555)) % worldSize == rank)
        {
            graphData->cols2.push_back(p);
        }
    }

    #ifdef MPI_DEBUG
    std::cout << "Rank " << rank << " received " << graphData->rows1.size()
              << " rows for graph 1" << std::endl;
    std::cout << "Rank " << rank << " received " << graphData->cols1.size()
              << " cols for graph 1" << std::endl;
    std::cout << "Rank " << rank << " received " << graphData->rows2.size()
              << " rows for graph 2" << std::endl;
    std::cout << "Rank " << rank << " received " << graphData->cols2.size()
              << " cols for graph 2" << std::endl;
    #endif
    InitializeGraphMemory(graphData, vertexCount);
    if (graphData->cols1.size() > 0)
    {
        for (int i = 0; i < graphData->cols1.size(); i++)
        {
            int srcColNum = graphData->cols1[i];
            for (int j = 0; j < vertexCount; j++)
            {
                graphData->colors1CurrentCols[i * vertexCount + j] = colors1[j * vertexCount + srcColNum];
            }
        }
    }
    if (graphData->rows1.size() > 0)
    {
        for (int i = 0; i < graphData->rows1.size(); i++)
        {
            int srcRowNum = graphData->rows1[i];
            for (int j = 0; j < vertexCount; j++)
            {
                graphData->colors1CurrentRows[i * vertexCount + j] = colors1[srcRowNum * vertexCount + j];
            }
        }
    }
    if (graphData->cols2.size() > 0)
    {
        for (int i = 0; i < graphData->cols2.size(); i++)
        {
            int srcColNum = graphData->cols2[i];
            for (int j = 0; j < vertexCount; j++)
            {
                graphData->colors2CurrentCols[i * vertexCount + j] = colors2[j * vertexCount + srcColNum];
            }
        }
    }
    if (graphData->rows2.size() > 0)
    {
        for (int i = 0; i < graphData->rows2.size(); i++)
        {
            int srcRowNum = graphData->rows2[i];
            for (int j = 0; j < vertexCount; j++)
            {
                graphData->colors2CurrentRows[i * vertexCount + j] = colors2[srcRowNum * vertexCount + j];
            }
        }
    }

    delete[] rowCounts1;
    delete[] colCounts1;
    delete[] rowCounts2;
    delete[] colCounts2;
    delete[] rowXor1;
    delete[] colXor1;
    delete[] rowXor2;
    delete[] colXor2;
    return graphData;
}

GraphData *initializeFromSparse(const char *g1f, const char *g2f, int vertexCount, int rank, int worldSize)
{
    long *rowCounts1 = new long[vertexCount];
    long *colCounts1 = new long[vertexCount];
    long *rowCounts2 = new long[vertexCount];
    long *colCounts2 = new long[vertexCount];
    long *rowXor1 = new long[vertexCount];
    long *colXor1 = new long[vertexCount];
    long *rowXor2 = new long[vertexCount];
    long *colXor2 = new long[vertexCount];
    COO g1 = read_matrix_market_to_COO(g1f);
    COO g2 = read_matrix_market_to_COO(g2f);
    for (int k = 0; k < vertexCount; k++)
    {
        rowCounts1[k] = 0;
        colCounts1[k] = 0;
        rowCounts2[k] = 0;
        colCounts2[k] = 0;
        rowXor1[k] = 0;
        colXor1[k] = 0;
        rowXor2[k] = 0;
        colXor2[k] = 0;
    }

    parallel_flat_hash_map<std::string, int, std::hash<string>,
                           std::equal_to<string>,
                           std::allocator<std::pair<const string, int>>,
                           4,
                           std::mutex>
        graph1RowsNonZero;
    parallel_flat_hash_map<std::string, int, std::hash<string>,
                           std::equal_to<string>,
                           std::allocator<std::pair<const string, int>>,
                           4,
                           std::mutex>
        graph1ColsNonZero;
    std::atomic<int> atomic(0);
    for (int i = 0; i < g1.nnz; i++)
    {
        int key[3];
        key[0] = 0;
        key[1] = g1.row_id[i];
        rowCounts1[key[1]]++;
        rowXor1[key[1]] ^= 1;
        key[2] = g1.col_id[i];
        colCounts1[key[2]]++;
        colXor1[key[2]] ^= 1;
        GetColor(key, &graph1RowsNonZero, atomic);
        GetColor(key, &graph1ColsNonZero, atomic);
    }
    free(g1.row_id);
    free(g1.col_id);
    free(g1.values);

    parallel_flat_hash_map<std::string, int, std::hash<string>,
                           std::equal_to<string>,
                           std::allocator<std::pair<const string, int>>,
                           4,
                           std::mutex>
        graph2RowsNonZero;
    parallel_flat_hash_map<std::string, int, std::hash<string>,
                           std::equal_to<string>,
                           std::allocator<std::pair<const string, int>>,
                           4,
                           std::mutex>
        graph2ColsNonZero;
    for (int i = 0; i < g2.nnz; i++)
    {
        int key[3];
        key[0] = 0;
        key[1] = g2.row_id[i];
        rowCounts2[key[1]]++;
        rowXor2[key[1]] ^= 1;
        key[2] = g2.col_id[i];
        colCounts2[key[2]]++;
        colXor2[key[2]] ^= 1;
        GetColor(key, &graph2RowsNonZero, atomic);
        GetColor(key, &graph2ColsNonZero, atomic);
    }
    free(g2.row_id);
    free(g2.col_id);
    free(g2.values);

    GraphData *graphData = new GraphData();
    for (int p = 0; p < vertexCount; p++)
    {
        if (((rowCounts1[p] & 0xAAAAAAAAAAAAAAAA) | (rowXor1[p] & 0x5555555555555555)) % worldSize == rank)
        {
            graphData->rows1.push_back(p);
        }
        if (((colCounts1[p] & 0xAAAAAAAAAAAAAAAA) | (colXor1[p] & 0x5555555555555555)) % worldSize == rank)
        {
            graphData->cols1.push_back(p);
        }
        if (((rowCounts2[p] & 0xAAAAAAAAAAAAAAAA) | (rowXor2[p] & 0x5555555555555555)) % worldSize == rank)
        {
            graphData->rows2.push_back(p);
        }
        if (((colCounts2[p] & 0xAAAAAAAAAAAAAAAA) | (colXor2[p] & 0x5555555555555555)) % worldSize == rank)
        {
            graphData->cols2.push_back(p);
        }
    }
    
#if defined(MPI_DEBUG) || defined(PARTITIONING)
    std::cout << "Rank " << rank << " received " << graphData->rows1.size() << " rows for graph 1" << std::endl;
    std::cout << "Rank " << rank << " received " << graphData->cols1.size() << " cols for graph 1" << std::endl;
    std::cout << "Rank " << rank << " received " << graphData->rows2.size() << " rows for graph 2" << std::endl;
    std::cout << "Rank " << rank << " received " << graphData->cols2.size() << " cols for graph 2" << std::endl;
#endif
    InitializeGraphMemory(graphData, vertexCount);
    if (graphData->cols1.size() > 0)
    {
        for (int i = 0; i < graphData->cols1.size(); i++)
        {
            int srcColNum = graphData->cols1[i];
            for (int j = 0; j < vertexCount; j++)
            {
                int key[3];
                key[0] = 0;
                key[1] = j;
                key[2] = srcColNum;
                if (GetColor(key, &graph1ColsNonZero, atomic).preexisting)
                {
                    graphData->colors1CurrentCols[i * vertexCount + j] = 1;
                }
                else
                {
                    graphData->colors1CurrentCols[i * vertexCount + j] = 0;
                }
            }
        }
    }
    if (graphData->rows1.size() > 0)
    {
        for (int i = 0; i < graphData->rows1.size(); i++)
        {
            int srcRowNum = graphData->rows1[i];
            int rowSetCount = 0;
            for (int j = 0; j < vertexCount; j++)
            {
                int key[3];
                key[0] = 0;
                key[1] = srcRowNum;
                key[2] = j;
                if (GetColor(key, &graph1RowsNonZero, atomic).preexisting)
                {
                    graphData->colors1CurrentRows[i * vertexCount + j] = 1;
                    rowSetCount++;
                }
                else
                {
                    graphData->colors1CurrentRows[i * vertexCount + j] = 0;
                }
            }
        }
    }
    if (graphData->cols2.size() > 0)
    {
        for (int i = 0; i < graphData->cols2.size(); i++)
        {
            int srcColNum = graphData->cols2[i];
            for (int j = 0; j < vertexCount; j++)
            {
                int key[3];
                key[0] = 0;
                key[1] = j;
                key[2] = srcColNum;
                if (GetColor(key, &graph2ColsNonZero, atomic).preexisting)
                {
                    graphData->colors2CurrentCols[i * vertexCount + j] = 1;
                }
                else
                {
                    graphData->colors2CurrentCols[i * vertexCount + j] = 0;
                }
            }
        }
    }
    if (graphData->rows2.size() > 0)
    {
        for (int i = 0; i < graphData->rows2.size(); i++)
        {
            int srcRowNum = graphData->rows2[i];
            for (int j = 0; j < vertexCount; j++)
            {
                int key[3];
                key[0] = 0;
                key[1] = srcRowNum;
                key[2] = j;
                if (GetColor(key, &graph2RowsNonZero, atomic).preexisting)
                {
                    graphData->colors2CurrentRows[i * vertexCount + j] = 1;
                }
                else
                {
                    graphData->colors2CurrentRows[i * vertexCount + j] = 0;
                }
            }
        }
    }
    delete[] rowCounts1;
    delete[] colCounts1;
    delete[] rowCounts2;
    delete[] colCounts2;
    delete[] rowXor1;
    delete[] colXor1;
    delete[] rowXor2;
    delete[] colXor2;
    return graphData;
}

GraphData *initializeFromSparseLinear(const char *g1f, const char *g2f, int vertexCount, int rank, int worldSize)
{
    long *rowCounts1 = new long[vertexCount];
    long *colCounts1 = new long[vertexCount];
    long *rowCounts2 = new long[vertexCount];
    long *colCounts2 = new long[vertexCount];
    
    long *rowXor1 = new long[vertexCount];
    long *colXor1 = new long[vertexCount];
    long *rowXor2 = new long[vertexCount];
    long *colXor2 = new long[vertexCount];

    
    for (int k = 0; k < vertexCount; k++)
    {
        rowCounts1[k] = 0;
        colCounts1[k] = 0;
        rowCounts2[k] = 0;
        colCounts2[k] = 0;
        rowXor1[k] = 0;
        colXor1[k] = 0;
        rowXor2[k] = 0;
        colXor2[k] = 0;
    }
    read_matrix_market_into_counts_and_xor(g1f, &colCounts1, &rowCounts1, &colXor1, &rowXor1, vertexCount);
    read_matrix_market_into_counts_and_xor(g2f, &colCounts2, &rowCounts2, &colXor2, &rowXor2, vertexCount);
    GraphData *graphData = new GraphData();
    for (int p = 0; p < vertexCount; p++)
    {
        if (((rowCounts1[p] & 0xAAAAAAAAAAAAAAAA) | (rowXor1[p] & 0x5555555555555555)) % worldSize == rank)
        {
            graphData->rows1.push_back(p);
        }
        if (((colCounts1[p] & 0xAAAAAAAAAAAAAAAA) | (colXor1[p] & 0x5555555555555555)) % worldSize == rank)
        {
            graphData->cols1.push_back(p);
        }
        if (((rowCounts2[p] & 0xAAAAAAAAAAAAAAAA) | (rowXor2[p] & 0x5555555555555555)) % worldSize == rank)
        {
            graphData->rows2.push_back(p);
        }
        if (((colCounts2[p] & 0xAAAAAAAAAAAAAAAA) | (colXor2[p] & 0x5555555555555555)) % worldSize == rank)
        {
            graphData->cols2.push_back(p);
        }
    }

#if defined(MPI_DEBUG) || defined(PARTITIONING)
    std::cout << "Rank " << rank << " received " << graphData->rows1.size() << " rows for graph 1" << std::endl;
    std::cout << "Rank " << rank << " received " << graphData->cols1.size() << " cols for graph 1" << std::endl;
    std::cout << "Rank " << rank << " received " << graphData->rows2.size() << " rows for graph 2" << std::endl;
    std::cout << "Rank " << rank << " received " << graphData->cols2.size() << " cols for graph 2" << std::endl;
#endif
    InitializeGraphMemory(graphData, vertexCount);
    read_matrix_market_into_partition(g1f, graphData->rows1, graphData->cols1, graphData->colors1CurrentRows, graphData->colors1CurrentCols);
    read_matrix_market_into_partition(g2f, graphData->rows2, graphData->cols2, graphData->colors2CurrentRows, graphData->colors2CurrentCols);
    delete[] rowCounts1;
    delete[] colCounts1;
    delete[] rowCounts2;
    delete[] colCounts2;
    delete[] rowXor1;
    delete[] colXor1;
    delete[] rowXor2;
    delete[] colXor2;
    return graphData;
}

int maxColor(GraphData *graphData, int vertexCount, bool useCurrent)
{
    int *colors1Cols;
    int *colors1Rows;
    int *colors2Cols;
    int *colors2Rows;
    if (useCurrent)
    {
        colors1Cols = graphData->colors1CurrentCols;
        colors1Rows = graphData->colors1CurrentRows;
        colors2Cols = graphData->colors2CurrentCols;
        colors2Rows = graphData->colors2CurrentRows;
    }
    else
    {
        colors1Cols = graphData->colors1NextCols;
        colors1Rows = graphData->colors1NextRows;
        colors2Cols = graphData->colors2NextCols;
        colors2Rows = graphData->colors2NextRows;
    }
    int max = -1;
    int cols1Max = -1;
    for (long i = 0; i < graphData->cols1.size(); i++)
    {
        for (long j = 0; j < vertexCount; j++)
        {
            if (colors1Cols[i * vertexCount + j] > cols1Max)
            {
                cols1Max = colors1Cols[i * vertexCount + j];
            }
        }
    }
    int rows1Max = -1;
    for (long i = 0; i < graphData->rows1.size(); i++)
    {
        for (long j = 0; j < vertexCount; j++)
        {
            if (colors1Rows[i * vertexCount + j] > rows1Max)
            {
                rows1Max = colors1Rows[i * vertexCount + j];
            }
        }
    }
    int cols2Max = -1;
    for (long i = 0; i < graphData->cols2.size(); i++)
    {
        for (long j = 0; j < vertexCount; j++)
        {
            if (colors2Cols[i * vertexCount + j] > cols2Max)
            {
                cols2Max = colors2Cols[i * vertexCount + j];
            }
        }
    }
    int rows2Max = -1;
    for (long i = 0; i < graphData->rows2.size(); i++)
    {
        for (long j = 0; j < vertexCount; j++)
        {
            if (colors2Rows[i * vertexCount + j] > rows2Max)
            {
                rows2Max = colors2Rows[i * vertexCount + j];
            }
        }
    }
    if (cols1Max > max)
    {
        max = cols1Max;
    }
    if (rows1Max > max)
    {
        max = rows1Max;
    }
    if (cols2Max > max)
    {
        max = cols2Max;
    }
    if (rows2Max > max)
    {
        max = rows2Max;
    }
    return max;
}

/* This function partitions a[] in three parts
   a) a[l..i] contains all elements smaller than pivot
   b) a[i+1..j-1] contains all occurrences of pivot
   c) a[j..r] contains all elements greater than pivot */
template <typename T>
void partition(T* a, int l, int r, int& i, int& j)
{
    i = l - 1, j = r;
    int p = l - 1, q = r;
    T v = a[r];
 
    while (true) {
        // From left, find the first element greater than
        // or equal to v. This loop will definitely
        // terminate as v is last element
        while (a[++i] < v)
            ;
 
        // From right, find the first element smaller than
        // or equal to v
        while (v < a[--j])
            if (j == l)
                break;
 
        // If i and j cross, then we are done
        if (i >= j)
            break;
 
        // Swap, so that smaller goes on left greater goes
        // on right
        swap(a[i], a[j]);
 
        // Move all same left occurrence of pivot to
        // beginning of array and keep count using p
        if (a[i] == v) {
            p++;
            swap(a[p], a[i]);
        }
 
        // Move all same right occurrence of pivot to end of
        // array and keep count using q
        if (a[j] == v) {
            q--;
            swap(a[j], a[q]);
        }
    }
 
    // Move pivot element to its correct index
    swap(a[i], a[r]);
 
    // Move all left same occurrences from beginning
    // to adjacent to arr[i]
    j = i - 1;
    for (int k = l; k < p; k++, j--)
        swap(a[k], a[j]);
 
    // Move all right same occurrences from end
    // to adjacent to arr[i]
    i = i + 1;
    for (int k = r - 1; k > q; k--, i++)
        swap(a[i], a[k]);
}
 
// 3-way partition based quick sort
template <typename T>
void quicksort(T* a, int l, int r)
{
    if (r <= l)
        return;
 
    int i, j;
 
    // Note that i and j are passed as reference
    partition(a, l, r, i, j);
 
    // Recur
    quicksort(a, l, j);
    quicksort(a, i, r);
}

bool RowCountsMatchForThisPartition(GraphData *graphData, int vertexCount)
{
    bool isMatching = true;
    if (graphData->cols1.size() != graphData->cols2.size())
    {
        isMatching = false;
    }
    if (graphData->rows1.size() != graphData->rows2.size())
    {
        isMatching = false;
    }
    return isMatching;
}

void fillPrefixSum(int *arr, int n, int *prefixSum)
{
    prefixSum[0] = arr[0];
    for (int i = 1; i < n; i++)
        prefixSum[i] = prefixSum[i - 1] + arr[i];
}

bool RowsEqual(int i, int j, int *colors1, int *colors2, int colors1Size, int colors2Size, int vertexCount)
{
    int *colorsFori;
    int *colorsForj;
    int offsetRowi;
    int offsetRowj;
    if (i < colors1Size)
    {
        offsetRowi = i * vertexCount;
        colorsFori = colors1;
    }
    else
    {
        i = i -= colors1Size;
        offsetRowi = i * vertexCount;
        colorsFori = colors2;
    }
    if (j < colors1Size)
    {
        offsetRowj = j * vertexCount;
        colorsForj = colors1;
    }
    else
    {
        j = j -= colors1Size;
        offsetRowj = j * vertexCount;
        colorsForj = colors2;
    }
    bool rowsEqual = true;
    for (int k = 0; k < vertexCount; k++)
    {
        if (colorsFori[offsetRowi + k] != colorsForj[offsetRowj + k])
        {
            rowsEqual = false;
            break;
        }
    }
    return rowsEqual;
}

void LargeBinPreprocess(int changeIndex,
                  int *changeIndexes,
                  int vertexCount,
                  bool *alreadyMatchedLeader,
                  int *rowColors,
                  int *rowCounts,
                  int *colors1,
                  int *colors2,
                  int colors1Size,
                  int colors2Size,
                  int *threadAssignments,
                  int rank,
                  bool (*signatureComparer)(int, int, int *, int *, int, int, int))
{
    for (long p = 0; p < changeIndex; p++)
    {
        int i = changeIndexes[p];
        int end;
        if (p + 1 == changeIndex)
        {
            end = colors1Size + colors2Size;
        }
        else
        {
            end = changeIndexes[p + 1];
        }
        if(end - i > 2000)
        {
            #ifdef LARGE_BIN_PREPROCESS_TIME
            clock_t before = clock() / (CLOCKS_PER_SEC / 1000);
            auto b = std::chrono::high_resolution_clock::now();
            #endif
            int numThreads = omp_get_max_threads();
            #pragma omp parallel for
            for(int t = 0; t < numThreads; t++)
            {
                int rowsPerThread = (end - i)/numThreads; 
                int threadStart = i + (rowsPerThread * t);
                int threadEnd = t + 1 == numThreads? end: threadStart + rowsPerThread; 
                while (threadStart < threadEnd)
                {
                    
                    if (!alreadyMatchedLeader[threadStart])
                    {
                        for (long j = threadStart + 1; j < threadEnd; j++)
                        {
                            if (signatureComparer(rowColors[threadStart], rowColors[j], colors1, colors2, colors1Size, colors2Size, vertexCount))
                            {
                                rowColors[j] = rowColors[threadStart];
                                alreadyMatchedLeader[j] = true;
                            }
                        }
                    }
                    threadStart++;
                }
            }
            #ifdef LARGE_BIN_PREPROCESS_TIME
            clock_t after = clock() / (CLOCKS_PER_SEC / 1000);
            auto a = std::chrono::high_resolution_clock::now();
            std::cout << "Wall bin time for preprocessing bin of size " << end - i << " on " << rank << ": " << getTimeDiff(b, a) << std::endl;
            std::cout << "Clock bin time for preprocessing bin of size " << end - i << " on " << rank << ": "
                      << to_string((double)(after - before) / 1000.0) << std::endl;
            #endif
        }
    }
}

void ThreadWorker(int changeIndex,
                  int *changeIndexes,
                  int vertexCount,
                  bool *alreadyMatchedLeader,
                  int *rowColors,
                  int *rowCounts,
                  int *colors1,
                  int *colors2,
                  int colors1Size,
                  int colors2Size,
                  int threadNum,
                  int *threadAssignments,
                  bool (*signatureComparer)(int, int, int *, int *, int, int, int))
{
    for (long p = 0; p < changeIndex; p++)
    {
        if (threadAssignments[p] == threadNum)
        {
            int i = changeIndexes[p];
            int end;
            if (p + 1 == changeIndex)
            {
                end = colors1Size + colors2Size;
            }
            else
            {
                end = changeIndexes[p + 1];
            }
            // This while loop would go in a thread defined by threadAssignment[p]

            while (i < end)
            {
                if (!alreadyMatchedLeader[i])
                {
                    for (long j = i + 1; j < end; j++)
                    {
                        if (!alreadyMatchedLeader[j])
                        {
                            if (signatureComparer(rowColors[i], rowColors[j], colors1, colors2, colors1Size, colors2Size, vertexCount))
                            {
                                int oldJColor = rowColors[j];
                                rowColors[j] = rowColors[i];
                                alreadyMatchedLeader[j] = true;
                                if(end - i > 2000)
                                {
                                    for(int k = j + 1; k < end; k++)
                                    {
                                        if(rowColors[k] == oldJColor)
                                        {
                                            rowColors[k] = rowColors[i];
                                            alreadyMatchedLeader[k] = true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                i++;
            }
        }
    }
}

void GetRowColors(int *colors1, int *colors2, int *rowColors, int *rowColorLoc, int colors1Size, int colors2Size, int vertexCount, int rank, long& totalBinWork)
{
    long numRowsTotal = colors1Size + colors2Size;
    if (numRowsTotal == 0)
    {
        return;
    }
    int *rowCounts = new int[numRowsTotal];
    int* rowXors = new int[numRowsTotal];
#pragma omp parallel for
    for (long i = 0; i < colors1Size; i++)
    {
        int rowTotal1 = 0;
        int rowXor1 = 0;
        for (long j = 0; j < vertexCount; j++)
        {
            rowTotal1 += colors1[i * vertexCount + j];
            rowXor1 ^= colors1[i * vertexCount + j];
        }
        rowCounts[i] = ((rowTotal1 & 0xAAAAAAAA) | (rowXor1 & 0x55555555));
        rowColors[i] = i;
    }
#pragma omp parallel for
    for (long i = 0; i < colors2Size; i++)
    {
        int rowTotal2 = 0;
        int rowXor2 = 0;
        for (long j = 0; j < vertexCount; j++)
        {
            rowTotal2 += colors2[i * vertexCount + j];
            rowXor2 ^= colors2[i * vertexCount + j];
        }
        rowCounts[i + colors1Size] = ((rowTotal2 & 0xAAAAAAAA) | (rowXor2 & 0x55555555));
        rowColors[i + colors1Size] = i + colors1Size;
    }

    sortValueAndLocationPair(rowCounts, rowColors, colors1Size + colors2Size);

#pragma omp parallel for
    for (long z = 0; z < colors1Size + colors2Size; z++)
    {
        rowColorLoc[rowColors[z]] = z;
    }
    if (numRowsTotal < 2)
    {
        delete[] rowCounts;
        delete[] rowXors;
        return;
    }
    
    int *changeIndexes = new int[colors1Size + colors2Size];
    int currentValue = -1;
    int changeIndex = 0;
    for (long p = 0; p < colors1Size + colors2Size; p++)
    {
        if (rowCounts[p] != currentValue)
        {
            currentValue = rowCounts[p];
            changeIndexes[changeIndex] = p;
            changeIndex++;
        }
    }
    #ifdef OMP_THREAD_DEBUG
    int *wpbCopy = new int[changeIndex];
    #endif
    int *workPerBin = new int[changeIndex];
    int *workBinLoc = new int[changeIndex];
    int *workPrefixSum = new int[changeIndex];
    int *threadAssignment = new int[changeIndex];
    #ifdef TOTAL_BIN_WORK
    std::atomic<int> totalBinWorkAtomic(0);
    #endif
#pragma omp parallel for
    for (long p = 0; p < changeIndex; p++)
    {
        int n  = 0;
        if (p + 1 == changeIndex)
        {
            n = colors1Size + colors2Size - changeIndexes[p];
            workPerBin[p] = (n * (n-1))/2;
        }
        else
        {
            n = changeIndexes[p + 1] - changeIndexes[p];
            workPerBin[p] = (n * (n-1))/2;
        }
        #ifdef TOTAL_BIN_WORK
        totalBinWorkAtomic+= workPerBin[p];
        #endif
    }
    #ifdef DEBUG_TOTAL_BIN_WORK
    std::cout << "rank " << rank << " work values." << std::endl; 
    for (int p = 0; p < changeIndex; p++)
    {
        std::cout << workPerBin[p] << " ";
    }
    std::cout << std::endl << std::endl;
    #endif
    #ifdef TOTAL_BIN_WORK
    totalBinWork += totalBinWorkAtomic;
    #endif
    fillPrefixSum(workPerBin, changeIndex, workPrefixSum);

    #ifdef OMP_THREAD_DEBUG
    printf("Maximum threads: %d\n", omp_get_max_threads());
    #endif
    
    int workPerThread = ceil(workPrefixSum[changeIndex - 1] / (float)omp_get_max_threads());
    #ifdef OMP_THREAD_DEBUG
    printf("Work per thread: %d\n", workPerThread);
    #endif
    int threadNum = 1;
    int workStartingPoint = 0;
    for (long p = 0; p < changeIndex; p++)
    {
        workBinLoc[p] = p;
    }
    #ifdef OMP_THREAD_DEBUG
    memcpy(wpbCopy, workPerBin, sizeof(int)* changeIndex);
    #endif
    sortValueAndLocationPair(workPerBin, workBinLoc, changeIndex);
    int nthreads = omp_get_max_threads();
    for (int p = 0; p < changeIndex; p++)
    {
        threadAssignment[workBinLoc[p]] = threadNum;
        threadNum++;
        if(threadNum > nthreads)
        {
            threadNum = 1;
        }
    }
    #ifdef OMP_THREAD_DEBUG
    std::cout << "Num bins: " << changeIndex << std::endl;
    std::cout << "Thread assignments:" << std::endl;
    for (int p = 0; p < changeIndex; p++)
    {
        std::cout << threadAssignment[p] << " ";
    }
    std::cout << std::endl;
    std::cout << "Work per bin:" << std::endl;
    for (int p = 0; p < changeIndex; p++)
    {
        std::cout << workPerBin[p] << " ";
    }
    std::cout << std::endl;
    std::cout << "Work bin loc:" << std::endl;
    for (int p = 0; p < changeIndex; p++)
    {
        std::cout << workBinLoc[p] << " ";
    }
    std::cout << std::endl;
    #endif
    #ifdef BIN_STATS
    std::cout << "Number of bins " << changeIndex - 1 << " on rank "<< rank << " and dim " << name << std::endl << std::endl; 
    for (int p = 0; p < changeIndex; p++)
    {
        if(p + 1 != changeIndex)
        {
            std::cout << "Bin " << p << " size: " << changeIndexes[p+1] - changeIndexes[p] << std::endl;
        }
    }
    std::cout << std::endl << std::endl;
    #endif
    bool *alreadyMatchedLeader = new bool[colors1Size + colors2Size];
#pragma omp parallel for
    for (int p = 0; p < colors1Size + colors2Size; p++)
    {
        alreadyMatchedLeader[p] = false;
    }
    #ifdef OMP_THREAD_DEBUG
    int num_procs = omp_get_num_procs();
    printf("Number of processors available: %d\n", num_procs);
    auto a = std::chrono::high_resolution_clock::now();
    #endif
    LargeBinPreprocess(changeIndex, changeIndexes, vertexCount, alreadyMatchedLeader, rowColors, rowCounts, colors1, colors2, colors1Size, colors2Size, threadAssignment, rank, RowsEqual);
    #pragma omp parallel for
    for (int p = 0; p < colors1Size + colors2Size; p++)
    {
        alreadyMatchedLeader[p] = false;
    }
#pragma omp parallel for
    for (int i = 1; i <= nthreads; i++)
    {
        #ifdef OMP_THREAD_DEBUG
        if(i == 1)
        {
            int num_threads = omp_get_num_threads();
            printf("Number of threads in current team: %d\n", num_threads);
        }
        int thisThreadsWork = 0;
        for(int j = 0; j < changeIndex; j++)
        {
            if(threadAssignment[j] == i)
            {
                thisThreadsWork+= wpbCopy[j];
            }
        }
        printf("Work for thread %d: %d\n", i, thisThreadsWork);
        #endif
        ThreadWorker(changeIndex, changeIndexes, vertexCount, alreadyMatchedLeader, rowColors, rowCounts, colors1, colors2, colors1Size, colors2Size, i, threadAssignment, RowsEqual);
    }
    #ifdef OMP_THREAD_DEBUG
    auto b = std::chrono::high_resolution_clock::now();
    std::cout << "Buckets time: " << getTimeDiff(a, b) << std::endl;
    delete[] wpbCopy;
    #endif
    delete[] changeIndexes;
    delete[] rowCounts;
    delete[] rowXors;
    delete[] workPerBin;
    delete[] workPrefixSum;
    delete[] threadAssignment;
    delete[] alreadyMatchedLeader;
    delete[] workBinLoc;
}

bool ColorCountsMatch(parallel_flat_hash_map<std::string, ColorCount*, std::hash<string>,
                                              std::equal_to<string>,
                                              std::allocator<std::pair<const string, ColorCount*>>,
                                              4,
                                              std::mutex> **colorMaps,
                                              bool* isLeaderBin,
                                              int numThreads)
{
    #ifdef COLOR_COUNT_CHECK_TIMES
    clock_t beforeColorCountCheck = clock() / (CLOCKS_PER_SEC / 1000);
    auto b_colorCountCheck = std::chrono::high_resolution_clock::now();
    #endif
    bool colorCountsMatch = true;
    bool done = false;
    for(int t = 0; t < numThreads; t++)
    {
        if(isLeaderBin[t])
        {
            #ifdef COLOR_COUNTS_ITERATION
            OUT << "Iterating over hashtable on thread: " << t << std::endl;
            #endif
            for(auto mapItr = colorMaps[t]->cbegin(); mapItr != colorMaps[t]->cend(); mapItr++)
            {
                if(done)
                {
                    break;
                }         
                if(mapItr->second->count != 0 && mapItr->second->count != std::numeric_limits<int>::min())
                {
                    OUT << "The value is " << mapItr->second->count << std::endl;
                    // No synchronization required here. All are setting to the same values.
                    colorCountsMatch = false;
                    done = true;
                }
            }    
        }
    }
    #ifdef COLOR_COUNT_CHECK_TIMES
    clock_t afterColorCountCheck = clock() / (CLOCKS_PER_SEC / 1000);
    auto a_colorCountCheck = std::chrono::high_resolution_clock::now();
    std::cout << "Color Count Check wall time: " << getTimeDiff(b_colorCountCheck, a_colorCountCheck) << std::endl;
    std::cout << "Color Count Check clock time: " << to_string((double)(afterColorCountCheck - beforeColorCountCheck) / 1000.0) << std::endl;
    #endif
    return colorCountsMatch;
}

int* ComputeBestTeam(int numThreads, int* rowBuckets, int* rowBucketsLoc, std::deque<int>* hashSplitsPerBin, bool* isLeaderBin, std::atomic<int>* ints, int vertexCount)
{
    if(numThreads > 1)
    {
        std::deque<int> followers;
        std::deque<int> leaders;
        double PERCENT_OF_STD_DEV = .10;
        int bucketNum = numThreads - 1;
        long total = 0;
        for(int i = 0; i < numThreads; i++)
        {
            total += rowBuckets[i];   
        }
        long mean = total/numThreads;
        long variance = 0;
        double stdDev = 0;
        for(int i = 0; i < numThreads; i++)
        {
            long absDiff = rowBuckets[i] - mean;
            variance += absDiff * absDiff;   
        }
        double initStdDev = sqrt(variance);
        while(bucketNum > -1)
        {
            if(rowBuckets[rowBucketsLoc[bucketNum]] - mean > PERCENT_OF_STD_DEV * stdDev)
            {
                leaders.push_back(rowBucketsLoc[bucketNum]);
            }
            bucketNum--;
        }
        for(int t = 0; t < numThreads; t++)
        {
            if(rowBuckets[t] == 0)
            {
                followers.push_back(t);
            }
        }
        while(leaders.size() > followers.size())
        {
            leaders.pop_back();
        }

        int bestLeaderCount = -1;
        double bestStdDev = 999999999999999.0;
        int* bestRowBucketsSoFar = new int[numThreads];
        int* tempRowBuckets = new int[numThreads];
        bool* bestIsLeaderStatusSoFar = new bool[numThreads];
        bool* tempIsLeaderStatus = new bool[numThreads];
        std::deque<int>* bestTeamsSoFar = new std::deque<int>[numThreads];
        bool foundSplit = false;
        while(leaders.size() > 0)
        {
            std::deque<int> leadersCopy = leaders;
            memcpy(tempRowBuckets, rowBuckets, numThreads * sizeof(int));
            memcpy(tempIsLeaderStatus, isLeaderBin, numThreads * sizeof(bool));
            std::deque<int> followerCopy = followers;
            while(followerCopy.size() > 0)
            {
                int leader = leadersCopy.front();
                leadersCopy.pop_front();
                leadersCopy.push_back(leader);
                int follower = followerCopy.back();
                tempIsLeaderStatus[follower] = false;
                followerCopy.pop_back();
                hashSplitsPerBin[leader].push_back(follower);
            }
            for(int t = 0; t < numThreads; t++)
            {
                if(hashSplitsPerBin[t].size() > 0)
                {
                    foundSplit = true;
                    int numOfBucketsForSplit = hashSplitsPerBin[t].size() + 1;
                    int rowsPerBin = rowBuckets[t]/numOfBucketsForSplit;
                    for(int i = 0; i < hashSplitsPerBin[t].size(); i++)
                    {
                        int thread = hashSplitsPerBin[t].back();
                        hashSplitsPerBin[t].pop_back();
                        hashSplitsPerBin[t].push_front(thread);
                        if(i + 1 == hashSplitsPerBin[t].size())
                        {
                            tempRowBuckets[thread] = rowsPerBin + (tempRowBuckets[t] % numOfBucketsForSplit);
                        }
                        else
                        {
                            tempRowBuckets[thread] = rowsPerBin;
                        }
                    }
                    tempRowBuckets[t] = rowsPerBin;
                }
            }
            long total = 0;
            for(int i = 0; i < numThreads; i++)
            {
                total += tempRowBuckets[i];   
            }
            long mean = total/numThreads;
            long variance = 0;
            double stdDev = 0;
            for(int i = 0; i < numThreads; i++)
            {
                long absDiff = tempRowBuckets[i] - mean;
                variance += absDiff * absDiff;   
            }
            stdDev = sqrt(variance);
            if(stdDev < bestStdDev)
            {
                bestStdDev = stdDev;
                bestLeaderCount = leadersCopy.size();
                memcpy(bestRowBucketsSoFar, tempRowBuckets, numThreads * sizeof(int));
                memcpy(bestIsLeaderStatusSoFar, tempIsLeaderStatus, numThreads * sizeof(bool));
                for(int t = 0; t < numThreads; t++)
                {
                    bestTeamsSoFar[t] = hashSplitsPerBin[t];
                }
            }
            for(int t = 0; t < numThreads; t++)
            {
                hashSplitsPerBin[t].clear();
            }
            leaders.pop_back();
        }
        
        if(bestLeaderCount != -1)
        {
            memcpy(rowBuckets, bestRowBucketsSoFar, numThreads * sizeof(int));
            memcpy(isLeaderBin, bestIsLeaderStatusSoFar, numThreads * sizeof(bool));
            #ifdef LEADERS_WITH_TEAMS_COUNT
            OUT << "Best leader team size is " << bestLeaderCount << std::endl;
            #endif
            foundSplit = true;
            for(int t = 0; t < numThreads; t++)
            {
                 hashSplitsPerBin[t] = bestTeamsSoFar[t];
            }
        }
        
        delete [] bestRowBucketsSoFar;
        delete [] tempRowBuckets;
        delete [] bestTeamsSoFar;
        delete [] tempIsLeaderStatus;
        delete [] bestIsLeaderStatusSoFar;
    }
}

bool MergeSplitTables(parallel_flat_hash_map<std::string, ColorCount*, std::hash<string>,
                                              std::equal_to<string>,
                                              std::allocator<std::pair<const string, ColorCount*>>,
                                              4,
                                              std::mutex> **colorMapAssignments, 
                                              std::atomic<int>** perRowIntAssignments, 
                                              std::atomic<int>* ints, 
                                              parallel_flat_hash_map<std::string, ColorCount*, std::hash<string>,
                                              std::equal_to<string>,
                                              std::allocator<std::pair<const string, ColorCount*>>,
                                              4,
                                              std::mutex> **colorMaps,
                                              parallel_flat_hash_map<int, int, std::hash<int>,
                                              std::equal_to<int>,
                                              std::allocator<std::pair<const int, int>>,
                                              4,
                                              std::mutex>& colorChanges,
                                              std::deque<int>* hashSplitsPerBin,
                                              int* threadIds,
                                              int numThreads,
                                              int vertexCount,
                                              int rank)
{
    #ifdef HASH_MERGE_TIMES
    std::cout << "Starting hash merge on rank " << rank << std::endl;
    clock_t beforeHashTableMerge = clock() / (CLOCKS_PER_SEC / 1000);
    auto b_hashtableMerge = std::chrono::high_resolution_clock::now();
    #endif
    bool mergeHappend = false;
    for(int t = 0; t < numThreads; t++)
    {
        if(hashSplitsPerBin[t].size() > 1)
        {
            mergeHappend = true;
            std::set<int> threadsToUpdateToLeader;
            while(hashSplitsPerBin[t].size() != 0)
            {
                int threadId = hashSplitsPerBin[t].back();
                hashSplitsPerBin[t].pop_back();
                if(threadId != t)
                {
                    threadsToUpdateToLeader.insert(threadId);
                    colorMaps[threadId]->for_each_m([&](std::pair<string, ColorCount*> keyValPair){
                        colorMaps[t]->lazy_emplace_l(keyValPair.first, 
                        [&keyValPair, &colorChanges](parallel_flat_hash_map<std::string, ColorCount*, std::hash<string>, std::equal_to<string>, std::allocator<std::pair<const string, ColorCount*>>, 4, std::mutex>::value_type &v)
                        {
                            if(keyValPair.second->count != std::numeric_limits<int>::min())
                            {
                                v.second->count += keyValPair.second->count;
                            }
                            int oldColor = keyValPair.second->color;
                            int newColor = v.second->color;
                            delete keyValPair.second;
                            keyValPair.second = NULL;
                            colorChanges.lazy_emplace_l(oldColor, [newColor](parallel_flat_hash_map<int, int, std::hash<int>, std::equal_to<int>, std::allocator<std::pair<const int, int>>, 4, std::mutex>::value_type &v)
                            {

                            },
                            [oldColor, newColor](const parallel_flat_hash_map<int, int, std::hash<int>,
                                                            std::equal_to<int>,
                                                            std::allocator<std::pair<const int, int>>,
                                                            4,
                                                            std::mutex>::constructor &ctor)
                            {
                                ctor(oldColor, newColor);   
                            });    
                            
                        },
                        [&keyValPair](const parallel_flat_hash_map<std::string, ColorCount*, std::hash<string>,
                                                            std::equal_to<string>,
                                                            std::allocator<std::pair<const string, ColorCount*>>,
                                                            4,
                                                            std::mutex>::constructor &ctor)
                        {

                            ctor(keyValPair.first, keyValPair.second);
                            keyValPair.second = NULL;   
                        });

                    });
                    colorMaps[threadId]->clear();
                }
            }
            for(int i = 0; i < 2 * vertexCount; i++)
            {
                if(threadsToUpdateToLeader.find(threadIds[i]) != threadsToUpdateToLeader.cend())
                {
                    threadIds[i] = t;
                    colorMapAssignments[i] = colorMaps[t];
                    perRowIntAssignments[i] = &ints[t];
                }
            }
            #ifdef REHASH_CHANGES_COUNT
            std::cout << "Number of color changes: " << colorChanges.size() << std::endl;
            #endif
        }
    }
    #ifdef HASH_MERGE_TIMES
    clock_t afterHashTableMerge = clock() / (CLOCKS_PER_SEC / 1000);
    auto a_hashtableMerge = std::chrono::high_resolution_clock::now();
    std::cout << "Hash Merge wall time: " << getTimeDiff(b_hashtableMerge, a_hashtableMerge) << std::endl;
    std::cout << "Hash Merge clock time: " << to_string((double)(afterHashTableMerge - beforeHashTableMerge) / 1000.0) << std::endl;
    #endif
    return mergeHappend;
}
HashResult hash1(GraphData *graphData,
           parallel_flat_hash_map<std::string, ColorCount*, std::hash<string>,
                                  std::equal_to<string>,
                                  std::allocator<std::pair<const string, ColorCount*>>,
                                  4,
                                  std::mutex> *colorMap,
           int vertexCount,
           int rank,
           int worldSize,
           long& totalBinWork, 
           int iteration)
{
    HashResult result;
    result.matchingGraphs = true;
    clock_t before = clock() / (CLOCKS_PER_SEC / 1000);
    auto b = std::chrono::high_resolution_clock::now();
#ifdef MPI_DEBUG
    std::cout << "World size: " << worldSize << std::endl;
    std::cout << "Rank: " << rank << std::endl;
#endif
#pragma omp parallel for
    for (long i = 0; i < graphData->rows1.size(); i++)
    {
        for (long j = 0; j < vertexCount; j++)
        {
            graphData->colors1SortedCurrentRows[i * vertexCount + j] = graphData->colors1CurrentRows[i * vertexCount + j];
        }
        quicksort(graphData->colors1SortedCurrentRows + (i * vertexCount), 0, vertexCount-1);
    }

#pragma omp parallel for
    for (long i = 0; i < graphData->rows2.size(); i++)
    {
        for (long j = 0; j < vertexCount; j++)
        {
            graphData->colors2SortedCurrentRows[i * vertexCount + j] = graphData->colors2CurrentRows[i * vertexCount + j];
        }
        quicksort(graphData->colors2SortedCurrentRows + (i * vertexCount), 0, vertexCount-1);
    }

#pragma omp parallel for
    for (long i = 0; i < graphData->cols1.size(); i++)
    {
        for (long j = 0; j < vertexCount; j++)
        {
            graphData->colors1SortedCurrentCols[i * vertexCount + j] = graphData->colors1CurrentCols[i * vertexCount + j];
        }
        quicksort(graphData->colors1SortedCurrentCols + (i * vertexCount), 0, vertexCount-1);
    }

#pragma omp parallel for
    for (long i = 0; i < graphData->cols2.size(); i++)
    {
        for (long j = 0; j < vertexCount; j++)
        {
            graphData->colors2SortedCurrentCols[i * vertexCount + j] = graphData->colors2CurrentCols[i * vertexCount + j];
        }
        quicksort(graphData->colors2SortedCurrentCols + (i * vertexCount), 0, vertexCount-1);
    }

    MPI_Barrier(MPI_COMM_WORLD);
    clock_t after = clock() / (CLOCKS_PER_SEC / 1000);
    auto a = std::chrono::high_resolution_clock::now();
#ifdef SORT_TIMES
    std::cout << "Sort wall time: " << getTimeDiff(b, a) << std::endl;
    std::cout << "Sort clock time: " << to_string((double)(after - before) / 1000.0) << std::endl;
#endif

    before = clock() / (CLOCKS_PER_SEC / 1000);
    b = std::chrono::high_resolution_clock::now();
    GetRowColors(graphData->colors1SortedCurrentRows, graphData->colors2SortedCurrentRows, graphData->allRowColors, graphData->allRowColorsLoc, graphData->rows1.size(), graphData->rows2.size(), vertexCount, rank, totalBinWork);
    after = clock() / (CLOCKS_PER_SEC / 1000);
    a = std::chrono::high_resolution_clock::now();
#ifdef COLORING_TIMES
    std::cout << "Rows wall time on rank " << rank << ": " << getTimeDiff(b, a) << std::endl;
    std::cout << "Rows clock time: " << to_string((double)(after - before) / 1000.0) << std::endl;
#endif
    b = std::chrono::high_resolution_clock::now();
    before = clock() / (CLOCKS_PER_SEC / 1000);
    GetRowColors(graphData->colors1SortedCurrentCols, graphData->colors2SortedCurrentCols, graphData->allColColors, graphData->allColColorsLoc, graphData->cols1.size(), graphData->cols2.size(), vertexCount, rank, totalBinWork);
    after = clock() / (CLOCKS_PER_SEC / 1000);
    a = std::chrono::high_resolution_clock::now();
#ifdef COLORING_TIMES
    std::cout << "Rows wall time on rank " << rank << ": " << getTimeDiff(b, a) << std::endl;
    std::cout << "Cols clock time: " << to_string((double)(after - before) / 1000.0) << std::endl;
#endif
    std::vector<int> rows1ColorData;
    for (int i = 0; i < graphData->rows1.size(); i++)
    {
        rows1ColorData.push_back(graphData->rows1[i]);
        rows1ColorData.push_back(graphData->allRowColors[graphData->allRowColorsLoc[i]] + (rank * (4 * vertexCount)));
    }
    std::vector<int> rows2ColorData;
    for (int i = 0; i < graphData->rows2.size(); i++)
    {
        rows2ColorData.push_back(graphData->rows2[i]);
        rows2ColorData.push_back(graphData->allRowColors[graphData->allRowColorsLoc[graphData->rows1.size() + i]] + (rank * (4 * vertexCount)));
    }

    std::vector<int> cols1ColorData;
    for (int i = 0; i < graphData->cols1.size(); i++)
    {
        cols1ColorData.push_back(graphData->cols1[i]);
        cols1ColorData.push_back(graphData->allColColors[graphData->allColColorsLoc[i]] + (rank * (4 * vertexCount)));
    }
    std::vector<int> cols2ColorData;
    for (int i = 0; i < graphData->cols2.size(); i++)
    {
        cols2ColorData.push_back(graphData->cols2[i]);
        cols2ColorData.push_back(graphData->allColColors[graphData->allColColorsLoc[graphData->cols1.size() + i]] + (rank * (4 * vertexCount)));
    }
    colorMap->clear();

    if (worldSize > 1)
    {
#ifdef COMM_TIMES
        clock_t before = clock() / (CLOCKS_PER_SEC / 1000);
        auto b = std::chrono::high_resolution_clock::now();
#endif
        int *rows1ColorSendData = rows1ColorData.data();
        int *rows2ColorSendData = rows2ColorData.data();
        int *cols1ColorSendData = cols1ColorData.data();
        int *cols2colorSendData = cols2ColorData.data();
#ifdef ALL_TO_ALL_V
        int mySendCounts = rows1ColorData.size() + rows2ColorData.size() + cols1ColorData.size() + cols2ColorData.size() + 5;
        int* recv_sizes = new int[worldSize];
        std::vector<int> send_sizes(worldSize, mySendCounts);
        MPI_Allgather(&mySendCounts, 1, MPI_INT, recv_sizes, 1, MPI_INT, MPI_COMM_WORLD);
        int size = 0;
        for (int i = 0; i < worldSize; i++) 
        {
            size += recv_sizes[i];
        }
        int *recvbuf = new int[size];
        std::vector<int> sdispls(worldSize, 0);
        int *rdispls = new int[worldSize];
        int* send_data = new int[mySendCounts];
        send_data[0] = 0;
        send_data[1] = rows1ColorData.size();
        send_data[2] = send_data[1] + rows2ColorData.size();
        send_data[3] = send_data[2] + cols1ColorData.size();
        send_data[4] = send_data[3] + cols2ColorData.size();
        if(rows1ColorData.size() > 0)
        {
            memcpy(send_data + 5, rows1ColorSendData, rows1ColorData.size() * sizeof(int));
        }
        if(rows2ColorData.size() > 0)
        {
            memcpy(send_data + 5 + send_data[1], rows2ColorSendData, rows2ColorData.size() * sizeof(int));
        }
        if(cols1ColorData.size() > 0)
        {
            memcpy(send_data + 5 + send_data[2], cols1ColorSendData, cols1ColorData.size() * sizeof(int));
        }
        if(cols2ColorData.size() > 0)
        {
            memcpy(send_data + 5 + send_data[3], cols2colorSendData, cols2ColorData.size() * sizeof(int));
        }
        int displacement = 0;
        for (int i = 0; i < worldSize; i++) {
            rdispls[i] = displacement;
            displacement += recv_sizes[i];
        }

    // Scatter data to all processes using MPI_Alltoallv
    MPI_Alltoallv(send_data, send_sizes.data(), sdispls.data(), MPI_INT,
                  recvbuf, recv_sizes, rdispls, MPI_INT, MPI_COMM_WORLD);
    for(int r = 0; r < worldSize; r++)
    {
        if(r != rank)
        {
            int* otherRankRecBuf = recvbuf + rdispls[r];
            for(int i = otherRankRecBuf[0]; i < otherRankRecBuf[1]; i+=2)
            {
                graphData->allRowColors1[otherRankRecBuf[5 + i]] = otherRankRecBuf[5 + i + 1];
            }
            for(int i = otherRankRecBuf[1]; i < otherRankRecBuf[2]; i+=2)
            {
                graphData->allRowColors2[otherRankRecBuf[5 + i]] = otherRankRecBuf[5 + i + 1];
            }
            for(int i = otherRankRecBuf[2]; i < otherRankRecBuf[3]; i+=2)
            {
                graphData->allColColors1[otherRankRecBuf[5 + i]] = otherRankRecBuf[5 + i + 1];
            }
            for(int i = otherRankRecBuf[3]; i < otherRankRecBuf[4]; i+=2)
            {
                graphData->allColColors2[otherRankRecBuf[5 + i]] = otherRankRecBuf[5 + i + 1];
            }
        }
    }
    
    delete [] recv_sizes;
    delete [] send_data;
    delete [] recvbuf;
    delete [] rdispls;
#endif
#ifdef CUSTOM_COMMS
#pragma omp parallel // starts a new team
        {
#pragma omp sections // divides the team into sections
            {
#pragma omp section
                {
                    if (rows1ColorData.size() > 0)
                    {
                        for (int i = 0; i < worldSize; i++)
                        {
                            if (i != rank)
                            {
                                if (MPI_Send(rows1ColorSendData, rows1ColorData.size(),
                                             MPI_INT, i, 0, MPI_COMM_WORLD) != MPI_SUCCESS)
                                {
#ifdef MPI_DEBUG
                                    std::cout << "Rank " << rank << " had a problem sending rows1."
                                              << std::endl;
#endif
                                }
                            }
                        }
                    }
                    else
                    {
                        for (int i = 0; i < worldSize; i++)
                        {
                            if (i != rank)
                            {
#ifdef MPI_DEBUG
                                std::cout << "Rank " << rank << " had a problem sending rows1."
                                          << std::endl;
#endif
                                int buff = 0;
                                MPI_Request myRequest;
                                if (MPI_Send(&buff, 1, MPI_INT, i, 0, MPI_COMM_WORLD) != MPI_SUCCESS)
                                {
#ifdef MPI_DEBUG
                                    std::cout << "We have a problem sending ping chat." << std::endl;
#endif
                                }
                            }
                        }
                    }
                }
#pragma omp section
                {
                    for (int i = 0; i < worldSize; i++)
                    {
                        if (i != rank)
                        {
                            MPI_Status status;
                            MPI_Status probeStatus;
                            MPI_Probe(MPI_ANY_SOURCE, 0, MPI_COMM_WORLD, &probeStatus);
                            int number;
                            MPI_Get_count(&probeStatus, MPI_INT, &number);
#ifdef MPI_DEBUG
                            std::cout << "The actual Count " << number << std::endl;
#endif
                            int *receivedData = new int[number];
#ifdef MPI_DEBUG
                            std::cout << "Rank " << rank << " starting receive rows1 from rank "
                                      << probeStatus.MPI_SOURCE << "." << std::endl;
#endif
                            if (MPI_Recv(receivedData, number, MPI_INT, probeStatus.MPI_SOURCE,
                                         0, MPI_COMM_WORLD, &status) != MPI_SUCCESS)
                            {
#ifdef MPI_DEBUG
                                std::cout << "Rank " << rank
                                          << " had a problem receiving rows1 from rank "
                                          << probeStatus.MPI_SOURCE << "." << std::endl;
#endif
                            }
#ifdef MPI_DEBUG
                            std::cout << "We got it on rank " << rank << " from rank " << i
                                      << std::endl;
#endif
                            int count;
                            MPI_Get_count(&status, MPI_INT, &count);
#ifdef MPI_DEBUG
                            std::cout << "The count for rank " << rank << " from rank "
                                      << probeStatus.MPI_SOURCE << " is " << count << std::endl;
#endif
                            if (count != 1)
                            {
#pragma omp parallel for
                                for (int c = 0; c < count; c += 2)
                                {
                                    graphData->allRowColors1[receivedData[c]] = receivedData[c + 1];
                                }
                            }

                            delete[] receivedData;
                        }
                    }
                }
            }
        }

        MPI_Barrier(MPI_COMM_WORLD);
#pragma omp parallel // starts a new team
        {
#pragma omp sections // divides the team into sections
            {
#pragma omp section
                {

                    if (rows2ColorData.size() > 0)
                    {
                        for (int i = 0; i < worldSize; i++)
                        {
                            if (i != rank)
                            {
                                if (MPI_Send(rows2ColorSendData, rows2ColorData.size(),
                                             MPI_INT, i, 0, MPI_COMM_WORLD) != MPI_SUCCESS)
                                {
#ifdef MPI_DEBUG
                                    std::cout << "Rank " << rank
                                              << " had a problem sending rows2." << std::endl;
#endif
                                }
                            }
                        }
                    }
                    else
                    {
                        for (int i = 0; i < worldSize; i++)
                        {
                            if (i != rank)
                            {
#ifdef MPI_DEBUG
                                std::cout << "Rank " << rank << " had a problem sending rows2."
                                          << std::endl;
#endif
                                int buff = 0;
                                if (MPI_Send(&buff, 1, MPI_INT, i, 0, MPI_COMM_WORLD) != MPI_SUCCESS)
                                {
#ifdef MPI_DEBUG
                                    std::cout << "We have a problem sending." << std::endl;
#endif
                                }
                            }
                        }
                    }
                }
#pragma omp section
                {
                    for (int i = 0; i < worldSize; i++)
                    {
                        if (i != rank)
                        {

                            MPI_Status status;
                            MPI_Status probeStatus;
                            MPI_Probe(MPI_ANY_SOURCE, 0, MPI_COMM_WORLD, &probeStatus);
                            int number;
                            MPI_Get_count(&probeStatus, MPI_INT, &number);
#ifdef MPI_DEBUG
                            std::cout << "The actual Count " << number << std::endl;
#endif
                            int *receivedData = new int[number];
#ifdef MPI_DEBUG
                            std::cout << "Rank " << rank << " starting receive rows2 from rank "
                                      << probeStatus.MPI_SOURCE << "." << std::endl;
#endif
                            if (MPI_Recv(receivedData, number, MPI_INT, probeStatus.MPI_SOURCE,
                                         0, MPI_COMM_WORLD, &status) != MPI_SUCCESS)
                            {
#ifdef MPI_DEBUG
                                std::cout << "Rank " << rank
                                          << " had a problem receiving rows2 from rank "
                                          << probeStatus.MPI_SOURCE << "." << std::endl;
#endif
                            }
#ifdef MPI_DEBUG
                            std::cout << "We got it on rank " << rank << " from rank "
                                      << probeStatus.MPI_SOURCE << std::endl;
#endif
                            int count;
                            MPI_Get_count(&status, MPI_INT, &count);
#ifdef MPI_DEBUG
                            std::cout << "The count for rank " << rank << " from rank "
                                      << probeStatus.MPI_SOURCE << " is " << count << std::endl;
#endif
                            if (count != 1)
                            {
#pragma omp parallel for
                                for (int c = 0; c < count; c += 2)
                                {
                                    graphData->allRowColors2[receivedData[c]] = receivedData[c + 1];
                                }
                            }

                            delete[] receivedData;
                        }
                    }
                }
            }
        }

        MPI_Barrier(MPI_COMM_WORLD);
#pragma omp parallel // starts a new team
        {
#pragma omp sections // divides the team into sections
            {
#pragma omp section
                {
                    if (cols1ColorData.size() > 0)
                    {
                        for (int i = 0; i < worldSize; i++)
                        {
                            if (i != rank)
                            {
                                if (MPI_Send(cols1ColorSendData, cols1ColorData.size(), MPI_INT, i,
                                             0, MPI_COMM_WORLD) != MPI_SUCCESS)
                                {
#ifdef MPI_DEBUG
                                    std::cout << "Rank " << rank << " had a problem sending cols1."
                                              << std::endl;
#endif
                                }
                            }
                        }
                    }
                    else
                    {
                        for (int i = 0; i < worldSize; i++)
                        {
                            if (i != rank)
                            {
#ifdef MPI_DEBUG
                                std::cout << "Rank " << rank << " had a problem sending cols1."
                                          << std::endl;
#endif
                                int buff = 0;
                                if (MPI_Send(&buff, 1, MPI_INT, i, 0, MPI_COMM_WORLD) != MPI_SUCCESS)
                                {
#ifdef MPI_DEBUG
                                    std::cout << "We have a problem here." << std::endl;
#endif
                                }
                            }
                        }
                    }
                }
#pragma omp section
                {
                    for (int i = 0; i < worldSize; i++)
                    {
                        if (i != rank)
                        {
                            MPI_Status status;
                            MPI_Status probeStatus;
                            MPI_Probe(MPI_ANY_SOURCE, 0, MPI_COMM_WORLD, &probeStatus);
                            int number;
                            MPI_Get_count(&probeStatus, MPI_INT, &number);
#ifdef MPI_DEBUG
                            std::cout << "The actual Count " << number << std::endl;
#endif
                            int *receivedData = new int[number];
#ifdef MPI_DEBUG
                            std::cout << "Rank " << rank << " starting receive cols1 from rank "
                                      << probeStatus.MPI_SOURCE << "." << std::endl;
#endif
                            if (MPI_Recv(receivedData, number, MPI_INT, probeStatus.MPI_SOURCE,
                                         0, MPI_COMM_WORLD, &status) != MPI_SUCCESS)
                            {
#ifdef MPI_DEBUG
                                std::cout << "Rank " << rank
                                          << " had a problem receiving cols1 from rank "
                                          << probeStatus.MPI_SOURCE << "." << std::endl;
#endif
                            }
#ifdef MPI_DEBUG
                            std::cout << "We got it on rank " << rank << " from rank "
                                      << probeStatus.MPI_SOURCE << std::endl;
#endif
                            int count;
                            MPI_Get_count(&status, MPI_INT, &count);
#ifdef MPI_DEBUG
                            std::cout << "The count for rank " << rank << " from rank "
                                      << probeStatus.MPI_SOURCE << " is " << count << std::endl;
#endif
                            if (count != 1)
                            {
#pragma omp parallel for
                                for (int c = 0; c < count; c += 2)
                                {
                                    graphData->allColColors1[receivedData[c]] = receivedData[c + 1];
                                }
                            }

                            delete[] receivedData;
                        }
                    }
                }
            }
        }

        MPI_Barrier(MPI_COMM_WORLD);
#pragma omp parallel // starts a new team
        {
#pragma omp sections // divides the team into sections
            {
#pragma omp section
                {
                    if (cols2ColorData.size() > 0)
                    {
                        for (int i = 0; i < worldSize; i++)
                        {
                            if (i != rank)
                            {
                                if (MPI_Send(cols2colorSendData, cols2ColorData.size(), MPI_INT, i,
                                             0, MPI_COMM_WORLD) != MPI_SUCCESS)
                                {
#ifdef MPI_DEBUG
                                    std::cout << "Rank " << rank << " had a problem sending cols2."
                                              << std::endl;
#endif
                                }
                            }
                        }
                    }
                    else
                    {
                        for (int i = 0; i < worldSize; i++)
                        {
                            if (i != rank)
                            {
#ifdef MPI_DEBUG
                                std::cout << "Rank " << rank << " had a problem sending cols2."
                                          << std::endl;
#endif
                                int buff = 0;
                                if (MPI_Send(&buff, 1, MPI_INT, i, 0, MPI_COMM_WORLD) != MPI_SUCCESS)
                                {
#ifdef MPI_DEBUG
                                    std::cout << "We have a problem here." << std::endl;
#endif
                                }
                            }
                        }
                    }
                }
#pragma omp section
                {
                    for (int i = 0; i < worldSize; i++)
                    {
                        if (i != rank)
                        {
                            MPI_Status status;
                            MPI_Status probeStatus;
                            MPI_Probe(MPI_ANY_SOURCE, 0, MPI_COMM_WORLD, &probeStatus);
                            int number;
                            MPI_Get_count(&probeStatus, MPI_INT, &number);
#ifdef MPI_DEBUG
                            std::cout << "The actual Count " << number << std::endl;
#endif
                            int *receivedData = new int[number];
#ifdef MPI_DEBUG
                            std::cout << "Rank " << rank << " starting receive cols2 from rank "
                                      << probeStatus.MPI_SOURCE << "." << std::endl;
#endif
                            if (MPI_Recv(receivedData, number, MPI_INT, probeStatus.MPI_SOURCE,
                                         0, MPI_COMM_WORLD, &status) != MPI_SUCCESS)
                            {
#ifdef MPI_DEBUG
                                std::cout << "Rank " << rank
                                          << " had a problem receiving cols2 from rank "
                                          << probeStatus.MPI_SOURCE << "." << std::endl;
#endif
                            }

#ifdef MPI_DEBUG
                            std::cout << "We got it on rank " << rank << " from rank "
                                      << probeStatus.MPI_SOURCE << std::endl;
#endif
                            int count;
                            MPI_Get_count(&status, MPI_INT, &count);
#ifdef MPI_DEBUG
                            std::cout << "The count for rank " << rank << " from rank "
                                      << probeStatus.MPI_SOURCE << " is " << count << std::endl;
#endif
                            if (count != 1)
                            {
#pragma omp parallel for
                                for (int c = 0; c < count; c += 2)
                                {
                                    graphData->allColColors2[receivedData[c]] = receivedData[c + 1];
                                }
                            }

                            delete[] receivedData;
                        }
                    }
                }
            }
        }

        MPI_Barrier(MPI_COMM_WORLD);
#endif
#pragma omp parallel for
        for (int i = 0; i < rows1ColorData.size(); i += 2)
        {
            graphData->allRowColors1[rows1ColorData[i]] = rows1ColorData[i + 1];
        }
#pragma omp parallel for
        for (int i = 0; i < rows2ColorData.size(); i += 2)
        {
            graphData->allRowColors2[rows2ColorData[i]] = rows2ColorData[i + 1];
        }
#pragma omp parallel for
        for (int i = 0; i < cols1ColorData.size(); i += 2)
        {
            graphData->allColColors1[cols1ColorData[i]] = cols1ColorData[i + 1];
        }
#pragma omp parallel for
        for (int i = 0; i < cols2ColorData.size(); i += 2)
        {
            graphData->allColColors2[cols2ColorData[i]] = cols2ColorData[i + 1];
        }
#ifdef COMM_TIMES
        clock_t after = clock() / (CLOCKS_PER_SEC / 1000);
        auto a = std::chrono::high_resolution_clock::now();
        std::cout << "Comm wall time on rank " << rank << ": " << getTimeDiff(b, a) << std::endl;
        std::cout << "Comm clock time on rank " << rank << ": "
                  << to_string((double)(after - before) / 1000.0) << std::endl;
#endif
    }
    else
    {
#pragma omp parallel for
        for (int i = 0; i < rows1ColorData.size(); i += 2)
        {
            graphData->allRowColors1[rows1ColorData[i]] = rows1ColorData[i + 1];
        }
#pragma omp parallel for
        for (int i = 0; i < rows2ColorData.size(); i += 2)
        {
            graphData->allRowColors2[rows2ColorData[i]] = rows2ColorData[i + 1];
        }
#pragma omp parallel for
        for (int i = 0; i < cols1ColorData.size(); i += 2)
        {
            graphData->allColColors1[cols1ColorData[i]] = cols1ColorData[i + 1];
        }
#pragma omp parallel for
        for (int i = 0; i < cols2ColorData.size(); i += 2)
        {
            graphData->allColColors2[cols2ColorData[i]] = cols2ColorData[i + 1];
        }
    }
    MPI_Barrier(MPI_COMM_WORLD);
    int numThreads = omp_get_max_threads();
    int* rowBuckets = new int[numThreads];
    int* rowBucketsSorted = new int[numThreads];
    int* rowBucketsLoc = new int[numThreads];
    int* threadIds = new int[2 * vertexCount];
    for(int init = 0; init < numThreads; init++)
    {
        rowBuckets[init] = 0;
        rowBucketsLoc[init] = init;
    }
    std::atomic<int>* ints = new std::atomic<int>[numThreads];
    parallel_flat_hash_map<std::string, ColorCount*, std::hash<string>,
                                  std::equal_to<string>,
                                  std::allocator<std::pair<const string, ColorCount*>>,
                                  4,
                                  std::mutex>** hashMaps = new parallel_flat_hash_map<std::string, ColorCount*, std::hash<string>,
                                  std::equal_to<string>,
                                  std::allocator<std::pair<const string, ColorCount*>>,
                                  4,
                                  std::mutex>*[numThreads];

    std::atomic<int>** perRowInts = new std::atomic<int>*[2 * vertexCount];
    parallel_flat_hash_map<std::string, ColorCount*, std::hash<string>,
                                  std::equal_to<string>,
                                  std::allocator<std::pair<const string, ColorCount*>>,
                                  4,
                                  std::mutex>** perRowMaps = new parallel_flat_hash_map<std::string, ColorCount*, std::hash<string>,
                                  std::equal_to<string>,
                                  std::allocator<std::pair<const string, ColorCount*>>,
                                  4,
                                  std::mutex>*[2 * vertexCount];

    for(int h = 0; h < numThreads; h++)
    {
        hashMaps[h] = new parallel_flat_hash_map<std::string, ColorCount*, std::hash<string>,
                                  std::equal_to<string>,
                                  std::allocator<std::pair<const string, ColorCount*>>,
                                  4,
                                  std::mutex>();
    }

    for(int t = 0; t < numThreads; t++)
    {
        for(int i = 0; i < vertexCount; i++)
        {
            if(graphData->allRowColors1[i] % numThreads == t)
            {
                rowBuckets[t]++;
            }    
        }
        for(int i = 0; i < vertexCount; i++)
        {
            if(graphData->allRowColors2[i] % numThreads == t)
            {
                rowBuckets[t]++;
            }
        }
    }
    memcpy(rowBucketsSorted, rowBuckets, sizeof(int) * numThreads);
#ifdef THREAD_DIM_COUNTS
    OUT << "Thread row count for rank " << rank << " before splits" << std::endl << std::endl;
    for(int i = 0; i < numThreads; i++)
    {
        OUT << "Number of rows for thread " << i << ": " << rowBuckets[i] << std::endl;
    }
#endif
    sortValueAndLocationPair(rowBucketsSorted, rowBucketsLoc, numThreads);
    std::deque<int>* hashSplitsPerBin = new std::deque<int>[numThreads];
    bool* isLeaderBin = new bool[numThreads];
    for(int i = 0; i < numThreads; i++)
    {
        isLeaderBin[i] = true;
    }
    bool* needsRehash = new bool[2 * vertexCount];
    for(int i = 0; i < 2 * vertexCount; i++)
    {
        needsRehash[i] = false;
    }
    
    ComputeBestTeam(numThreads,rowBuckets,rowBucketsLoc,hashSplitsPerBin,isLeaderBin,ints,vertexCount);
    #ifdef THREAD_DIM_COUNTS
    OUT << "Thread row count for rank " << rank << " after splits" << std::endl << std::endl;
    for(int i = 0; i < numThreads; i++)
    {
        OUT << "Number of rows for thread " << i << ": " << rowBuckets[i] << std::endl;
    }
    #endif
    #ifdef TEAMS_DEBUG
    for(int t = 0; t < numThreads; t++)
    {
        if(isLeaderBin[t])
        {
            std::cout << "Bin Group for thread " << t << ": ";
            for(auto pos = hashSplitsPerBin[t].begin(); pos != hashSplitsPerBin[t].end(); pos++)
            {
                std::cout << *pos << " ";
            }
            std::cout << std::endl;
        }
    }
    #endif
    for(int t = 0; t < numThreads; t++)
    {
        hashSplitsPerBin[t].push_back(t);
        for(int i = 0; i < vertexCount; i++)
        {
            if(graphData->allRowColors1[i] % numThreads == t)
            {
                bool assigned = false;
                while(!assigned)
                {
                    if(hashSplitsPerBin[t].size() > 1)
                    {
                        int thread = hashSplitsPerBin[t].back();
                        hashSplitsPerBin[t].pop_back();
                        hashSplitsPerBin[t].push_front(thread);
                        if(rowBuckets[thread] > 0)
                        {
                            needsRehash[i] = true;
                            assigned = true;
                            threadIds[i] = thread;
                            perRowMaps[i] = hashMaps[thread];
                            perRowInts[i] = &ints[thread];
                            rowBuckets[thread]--;
                        }
                    }
                    else
                    {
                        assigned = true;
                        threadIds[i] = t;
                        perRowMaps[i] = hashMaps[t];
                        perRowInts[i] = &ints[t];
                    }
                }
            }    
        }
        for(int i = 0; i < vertexCount; i++)
        {
            if(graphData->allRowColors2[i] % numThreads == t)
            {
                bool assigned = false;
                while(!assigned)
                {
                    if(hashSplitsPerBin[t].size() > 1)
                    {
                        int thread = hashSplitsPerBin[t].back();
                        hashSplitsPerBin[t].pop_back();
                        hashSplitsPerBin[t].push_front(thread);
                        if(rowBuckets[thread] > 0)
                        {
                            needsRehash[vertexCount + i] = true;
                            assigned = true;
                            threadIds[vertexCount + i] = thread;
                            perRowMaps[vertexCount + i] = hashMaps[thread];
                            perRowInts[vertexCount + i] = &ints[thread];
                            rowBuckets[thread]--;
                        }
                    }
                    else
                    {
                        assigned = true;
                        threadIds[vertexCount + i] = t;
                        perRowMaps[vertexCount + i] = hashMaps[t];
                        perRowInts[vertexCount + i] = &ints[t];
                    }
                }
            }
        }        
    }
    std::atomic<int> atomic(0);
#ifdef HASH_TABLE_TIMES_DEBUG
    std::cout << "Starting hashing on rank " << rank << std::endl;
    clock_t beforeHashTable = clock() / (CLOCKS_PER_SEC / 1000);
    auto b_hashtable = std::chrono::high_resolution_clock::now();
#endif
 #ifdef G1_HASH_TIMES
    clock_t beforeG1hash = clock() / (CLOCKS_PER_SEC / 1000);
    auto b_g1Hash = std::chrono::high_resolution_clock::now();
 #endif
    for(int t = 0; t < numThreads; t++)
    {
        ints[t].store(0);
    }
 #pragma omp parallel for
    for(int t = 0; t < numThreads; t++)
    {
        for (long i = 0; i < graphData->rows1.size(); i++)
        {
            for (long j = 0; j < vertexCount; j++)
            {
                if(threadIds[graphData->rows1[i]] == omp_get_thread_num())
                {
                    int signature[3];
                    int signatureLoc = 0;
                    signatureLoc = 0;
                    signature[signatureLoc] = graphData->colors1CurrentRows[i * vertexCount + j];
                    signatureLoc++;
                    signature[signatureLoc] = graphData->allColColors1[j];
                    signatureLoc++;
                    signature[signatureLoc] = graphData->allRowColors1[graphData->rows1[i]];
                    #ifdef HASH_SPLIT_AND_MERGE_DEBUG
                    GetColor(signature, colorMap, &atomic, true, true);
                    #endif
                    graphData->colors1NextRowsColorCountPtrs[i * vertexCount + j] = GetColor(signature, perRowMaps[graphData->rows1[i]], perRowInts[graphData->rows1[i]], true, true).colorInfo;
                }
                
            }
        }
        for (long i = 0; i < graphData->cols1.size(); i++)
        {
            for (long j = 0; j < vertexCount; j++)
            {
                if(threadIds[j] == omp_get_thread_num())
                {
                    int signature[3];
                    int signatureLoc = 0;
                    signatureLoc = 0;
                    signature[signatureLoc] = graphData->colors1CurrentCols[i * vertexCount + j];
                    signatureLoc++;
                    signature[signatureLoc] = graphData->allColColors1[graphData->cols1[i]];
                    signatureLoc++;
                    signature[signatureLoc] = graphData->allRowColors1[j];
                    #ifdef HASH_SPLIT_AND_MERGE_DEBUG
                    GetColor(signature,colorMap, &atomic , true, false);
                    #endif
                    graphData->colors1NextColsColorCountPtrs[i * vertexCount + j] = GetColor(signature, perRowMaps[j], perRowInts[j], true, false).colorInfo;
                }
            }
        }
    }
#ifdef G1_HASH_TIMES
    clock_t afterG1hash = clock() / (CLOCKS_PER_SEC / 1000);
    auto a_g1Hash = std::chrono::high_resolution_clock::now();
    std::cout << "G1 hash wall time: " << getTimeDiff(b_g1Hash, a_g1Hash) << std::endl;
    std::cout << "G1 hash clock time: " << to_string((double)(afterG1hash - beforeG1hash) / 1000.0) << std::endl;
#endif
#ifdef G2_HASH_TIMES
    clock_t beforeG2hash = clock() / (CLOCKS_PER_SEC / 1000);
    auto b_g2Hash = std::chrono::high_resolution_clock::now();
#endif
 #pragma omp parallel for
    for(int t = 0; t < numThreads; t++)
    {
        for (long i = 0; i < graphData->rows2.size(); i++)
        {
            for (long j = 0; j < vertexCount; j++)
            {
                if(threadIds[vertexCount + graphData->rows2[i]] == omp_get_thread_num())
                {
                    int signature[3];
                    int signatureLoc = 0;
                    signatureLoc = 0;
                    signature[signatureLoc] = graphData->colors2CurrentRows[i * vertexCount + j];
                    signatureLoc++;
                    signature[signatureLoc] = graphData->allColColors2[j];
                    signatureLoc++;
                    signature[signatureLoc] = graphData->allRowColors2[graphData->rows2[i]];
                    #ifdef HASH_SPLIT_AND_MERGE_DEBUG
                    GetColor(signature, colorMap, &atomic, false, graphData->colors2NextRows + i * vertexCount + j, true);
                    #endif
                    graphData->colors2NextRowsColorCountPtrs[i * vertexCount + j] = GetColor(signature, perRowMaps[vertexCount + graphData->rows2[i]], perRowInts[vertexCount + graphData->rows2[i]], false, true).colorInfo;
                }
            }
        }

        for (long i = 0; i < graphData->cols2.size(); i++)
        {
            for (long j = 0; j < vertexCount; j++)
            {
                if(threadIds[vertexCount + j] == omp_get_thread_num())
                {
                    int signature[3];
                    int signatureLoc = 0;
                    signatureLoc = 0;
                    signature[signatureLoc] = graphData->colors2CurrentCols[i * vertexCount + j];
                    signatureLoc++;
                    signature[signatureLoc] = graphData->allColColors2[graphData->cols2[i]];
                    signatureLoc++;
                    signature[signatureLoc] = graphData->allRowColors2[j];
                    #ifdef HASH_SPLIT_AND_MERGE_DEBUG
                    GetColor(signature, colorMap, &atomic, false, graphData->colors2NextCols + i * vertexCount + j, false);
                    #endif
                    graphData->colors2NextColsColorCountPtrs[i * vertexCount + j] = GetColor(signature, perRowMaps[vertexCount + j], perRowInts[vertexCount + j], false, false).colorInfo;
                }
            }
        }
    }
    #ifdef G2_HASH_TIMES
    clock_t afterG2hash = clock() / (CLOCKS_PER_SEC / 1000);
    auto a_g2Hash = std::chrono::high_resolution_clock::now();
    std::cout << "G2 hash wall time: " << getTimeDiff(b_g2Hash, a_g2Hash) << std::endl;
    std::cout << "G2 hash clock time: " << to_string((double)(afterG2hash - beforeG2hash) / 1000.0) << std::endl;
    #endif
    int* maxKeys = new int[worldSize];
    int* offsets = new int[worldSize];
    int* hashtableOffset = new int[numThreads];
    for(int t = 0; t < worldSize; t++)
    {
        maxKeys[t] = 0;
    }
    hashtableOffset[0] = 0;
    for(int t = 0; t < numThreads; t++)
    {
        maxKeys[rank] += hashMaps[t]->size();
        if(t > 0)
        {
            hashtableOffset[t] = hashtableOffset[t-1] + hashMaps[t-1]->size();
        }
    }
    for (int i = 0; i < worldSize; i++)
    {
        MPI_Bcast(maxKeys + i, 1, MPI_INT, i, MPI_COMM_WORLD);
    }
    offsets[0] = 0;
    for(int i = 1; i < worldSize; i++)
    {
        offsets[i] = offsets[i-1] + maxKeys[i-1];
    }
    
    #pragma omp parallel for
    for(int t = 0; t < numThreads; t++)
    {
        hashMaps[t]->for_each_m([&](std::pair<string, ColorCount*> keyValPair){
                        ColorCount* value = keyValPair.second;
                        value->color += offsets[rank] + hashtableOffset[t];
        });
    }
    #ifdef HASH_SPLIT_AND_MERGE_DEBUG
    colorMap->for_each_m([&](std::pair<string, ColorCount*> keyValPair){
                        ColorCount* value = keyValPair.second;
                        value->color += offsets[rank];
        });
    #endif
    delete [] maxKeys;
    delete [] offsets;
    delete [] hashtableOffset;

    #pragma omp parallel for
    for(int t = 0; t < numThreads; t++)
    {
        for (long i = 0; i < graphData->rows1.size(); i++)
        {
            for (long j = 0; j < vertexCount; j++)
            {
                if(threadIds[graphData->rows1[i]] == omp_get_thread_num())
                {
                    graphData->colors1NextRows[i * vertexCount + j] = graphData->colors1NextRowsColorCountPtrs[i * vertexCount + j]->color;
                }
                
            }
        }
        for (long i = 0; i < graphData->cols1.size(); i++)
        {
            for (long j = 0; j < vertexCount; j++)
            {
                if(threadIds[j] == omp_get_thread_num())
                {
                    graphData->colors1NextCols[i * vertexCount + j] = graphData->colors1NextColsColorCountPtrs[i * vertexCount + j]->color;
                }
            }
        }
    }


 #pragma omp parallel for
    for(int t = 0; t < numThreads; t++)
    {
        for (long i = 0; i < graphData->rows2.size(); i++)
        {
            for (long j = 0; j < vertexCount; j++)
            {
                if(threadIds[vertexCount + graphData->rows2[i]] == omp_get_thread_num())
                {
                    graphData->colors2NextRows[i * vertexCount + j] = graphData->colors2NextRowsColorCountPtrs[i * vertexCount + j]->color;
                }
            }
        }

        for (long i = 0; i < graphData->cols2.size(); i++)
        {
            for (long j = 0; j < vertexCount; j++)
            {
                if(threadIds[vertexCount + j] == omp_get_thread_num())
                {
                    graphData->colors2NextCols[i * vertexCount + j] = graphData->colors2NextColsColorCountPtrs[i * vertexCount + j]->color;
                }
            }
        }
    }
 parallel_flat_hash_map<int, int, std::hash<int>,
                                              std::equal_to<int>,
                                              std::allocator<std::pair<const int, int>>,
                                              4,
                                              std::mutex> colorChanges;
 if(MergeSplitTables(perRowMaps, perRowInts, ints, hashMaps, colorChanges, hashSplitsPerBin, threadIds, numThreads, vertexCount, rank))
 {
    #ifdef G1_AND_G2_REHASH_TIMES
    clock_t beforeG1Rehash = clock() / (CLOCKS_PER_SEC / 1000);
    auto b_g1Rehash = std::chrono::high_resolution_clock::now();
    #endif
    #pragma omp parallel
    {
        #pragma omp for
        for (long i = 0; i < graphData->rows1.size(); i++)
        {
            for (long j = 0; j < vertexCount; j++)
            {
                if(needsRehash[graphData->rows1[i]])
                {
                    colorChanges.if_contains_unsafe(graphData->colors1NextRows[i * vertexCount + j],[&graphData, i, vertexCount, j](parallel_flat_hash_map<int, int, std::hash<int>, std::equal_to<int>, std::allocator<std::pair<const int, int>>, 4, std::mutex>::value_type &v)
                    {
                        graphData->colors1NextRows[i * vertexCount + j] = v.second;
                    });
                }
            }
        }
        #pragma omp for
        for (long i = 0; i < graphData->cols1.size(); i++)
        {
            for (long j = 0; j < vertexCount; j++)
            {
                if(needsRehash[j])
                {
                    colorChanges.if_contains_unsafe(graphData->colors1NextCols[i * vertexCount + j],[&graphData, i, vertexCount, j](parallel_flat_hash_map<int, int, std::hash<int>, std::equal_to<int>, std::allocator<std::pair<const int, int>>, 4, std::mutex>::value_type &v)
                    {
                        graphData->colors1NextCols[i * vertexCount + j] = v.second;
                    });
                }
            }
        }
        #pragma omp for
        for (long i = 0; i < graphData->rows2.size(); i++)
        {
            for (long j = 0; j < vertexCount; j++)
            {
                if(needsRehash[vertexCount + graphData->rows2[i]])
                {
                    colorChanges.if_contains_unsafe(graphData->colors2NextRows[i * vertexCount + j],[&graphData, i, vertexCount, j](parallel_flat_hash_map<int, int, std::hash<int>, std::equal_to<int>, std::allocator<std::pair<const int, int>>, 4, std::mutex>::value_type &v)
                    {
                        graphData->colors2NextRows[i * vertexCount + j] = v.second;
                    });
                }
            }
        }
        #pragma omp for
        for (long i = 0; i < graphData->cols2.size(); i++)
        {
            for (long j = 0; j < vertexCount; j++)
            {
                if(needsRehash[vertexCount + j])
                { 
                    colorChanges.if_contains_unsafe(graphData->colors2NextCols[i * vertexCount + j],[&graphData, i, vertexCount, j](parallel_flat_hash_map<int, int, std::hash<int>, std::equal_to<int>, std::allocator<std::pair<const int, int>>, 4, std::mutex>::value_type &v)
                    {
                        graphData->colors2NextCols[i * vertexCount + j] = v.second;
                    });  
                }
            }
        }
    }
    #ifdef G1_AND_G2_REHASH_TIMES
    clock_t afterG1Rehash = clock() / (CLOCKS_PER_SEC / 1000);
    auto a_g1Rehash = std::chrono::high_resolution_clock::now();
    std::cout << "G1 and G2 Rehash wall time: " << getTimeDiff(b_g1Rehash, a_g1Rehash) << std::endl;
    std::cout << "G1 and G2 Rehash clock time: " << to_string((double)(afterG1Rehash - beforeG1Rehash) / 1000.0) << std::endl;
    #endif
 }
#ifdef HASH_TABLE_TIMES_DEBUG
    clock_t afterHashTable = clock() / (CLOCKS_PER_SEC / 1000);
    auto a_hashtable = std::chrono::high_resolution_clock::now();
    std::cout << "Hash Table Lookup time - cpu time, rank " << rank << ":" << to_string((double)(afterHashTable - beforeHashTable) / 1000.0) << std::endl;
    std::cout << "Hash Table Lookup time - wall time, rank " << rank << ":" << getTimeDiff(b_hashtable, a_hashtable) << std::endl;
#endif
    after = clock() / (CLOCKS_PER_SEC / 1000);
    a = std::chrono::high_resolution_clock::now();
#ifdef COLORING_TIMES
    std::cout << "Get new colors time for rank " << rank << ":" << to_string((double)(after - before) / 1000.0) << std::endl;
    std::cout << "Get new colors wall time for rank " << rank << ":" << getTimeDiff(b, a) << std::endl;
#endif
    result.matchingGraphs = ColorCountsMatch(hashMaps, isLeaderBin, numThreads);
    MPI_Barrier(MPI_COMM_WORLD);
    delete [] rowBuckets;
    delete [] rowBucketsSorted;
    delete [] rowBucketsLoc;
    delete [] ints;
    delete [] perRowInts;
    delete [] perRowMaps;
    delete [] threadIds;
    delete [] hashSplitsPerBin;
    delete [] needsRehash;
    #ifdef HASH_TABLE_KEY_LOAD
    std::cout << "Hash table key loads for rank " << rank << ":" << std::endl << std::endl;
    #endif
    #pragma omp parallel for
    for(int h = 0; h < numThreads; h++)
    {
        #ifdef HASH_TABLE_KEY_LOAD
        std::cout << "Hash table " << h << ": " << hashMaps[h]->size() << std::endl;
        #endif 
        if(isLeaderBin[h])
        {
            int size = 0;
            hashMaps[h]->for_each_m([&](std::pair<string, ColorCount*> keyValPair){
                        ColorCount* value = keyValPair.second;
                        if(value->count != std::numeric_limits<int>::min())
                        {
                            #pragma omp atomic
                            size += 1;
                        }
        });
            #pragma omp atomic
            result.maxColor += size;
        }
    }
    #ifdef HASH_SPLIT_AND_MERGE_DEBUG
    long aggregateLow = 99999999999;
    long aggregateHigh = -1;
    std::set<int> set1;
    std::set<int> set2;
    std::string hashTablesDebug;
    for(int h = 0; h < numThreads; h++)
    { 
        if(isLeaderBin[h])
        {
            aggregateLow = 99999999999;
            aggregateHigh = -1;
            hashMaps[h]->for_each_m([&](std::pair<string, ColorCount*> keyValPair){
                        ColorCount* value = keyValPair.second;
                        set1.insert(value->color);
                        if(value->color > aggregateHigh)
                        {
                            aggregateHigh = value->color;
                        }
                        if(value->color < aggregateLow)
                        {
                            aggregateLow = value->color;
                        }
            });
            hashTablesDebug += "Hash Table Leader " + to_string(h) + ": Aggregate High: " +  to_string(aggregateHigh) + " Aggregate Low: " + to_string(aggregateLow) + "\n";
        }
    }
    long bigHigh = -1;
    long bigLow = 99999999999;
    colorMap->for_each_m([&](std::pair<string, ColorCount*> keyValPair){
                    string key = keyValPair.first;
                    ColorCount* value = keyValPair.second;
                    set2.insert(value->color);
                    if(value->color > bigHigh)
                    {
                        bigHigh = value->color;
                    }
                    if(value->color < bigLow)
                    {
                        bigLow = value->color;
                    }
    });
    hashTablesDebug += "Big Hash Table: High: " + to_string(bigHigh) + " Low: " + to_string(bigLow) + "\n";
    std::cout << hashTablesDebug << std::endl;
    int colorMapSize = 0;
    colorMap->for_each_m([&](std::pair<string, ColorCount*> keyValPair){
        ColorCount* value = keyValPair.second;
        if(value->count != std::numeric_limits<int>::min())
        {
            #pragma omp atomic
            colorMapSize += 1;
        }
    });
    if(colorMapSize != result.maxColor)
    {
        
        std::cout << "The key counts are not equal: merged hashes: " << result.maxColor << " big hash: " << colorMapSize << std::endl;
    }
    else
    {
        if(set1.size() != set2.size())
        {
            std::cout << "rank " << rank << " The color counts are not equal. Big: " << set2.size() << "Aggregate: " << set1.size() << std::endl;
        } 
    }
    #endif
    #pragma omp parallel for
    for(int h = 0; h < numThreads; h++)
    {
        #ifdef HASH_TABLE_KEY_LOAD
        std::cout << "Hash table " << h << ": " << hashMaps[h]->size() << std::endl;
        #endif 
            hashMaps[h]->for_each_m([&](std::pair<string, ColorCount*> keyValPair){
                        
                        if(keyValPair.second != NULL)
                        {
                            delete keyValPair.second;
                            keyValPair.second = NULL;
                        }
                        
            });
        
        
        delete hashMaps[h];
    }
    #ifdef HASH_SPLIT_AND_MERGE_DEBUG
    colorMap->for_each_m([&](std::pair<string, ColorCount*> keyValPair){
                            if(keyValPair.second != NULL)
                            {
                                delete keyValPair.second;
                                keyValPair.second = NULL;
                            }
                        });
    #endif
    delete [] hashMaps;
    delete [] isLeaderBin;
    return result;
}

void initializeNextColors(int *newColors, int size)
{
    for (int i = 0; i < size; i++)
    {
        newColors[i] = -1;
    }
}

void initColorChangeVectors(bool *columnColorChange, bool *rowColorChange, int vertexCount)
{
    for (int i = 0; i < vertexCount; i++)
    {
        columnColorChange[i] = false;
        rowColorChange[i] = false;
    }
}

int CountChanges(bool *columnColorChange, bool *rowColorChange, int vertexCount)
{
    int changeCount = 0;
    for (int i = 0; i < vertexCount; i++)
    {
        if (columnColorChange[i])
        {
            changeCount++;
        }
        if (rowColorChange[i])
        {
            changeCount++;
        }
    }
    return changeCount;
}

void initColorMap(int *colorMap, int vertexCount)
{
    for (int i = 0; i < vertexCount * vertexCount * (2 * vertexCount + 1); i++)
    {
        colorMap[i] = -1;
    }
}

int GetColorCount(int *counts, int worldSize)
{
    int totalColors = 0;
    for (int i = 0; i < worldSize; i++)
    {
        totalColors += counts[i];
    }
    return totalColors;
}

bool SomeMaxColorIncreased(int *prevMaxes, int *currentMaxes, int worldSize)
{
    for (int i = 0; i < worldSize; i++)
    {
        if (prevMaxes[i] < currentMaxes[i])
        {
            return true;
        }
    }
    return false;
}

bool IsIsomorphic(unsigned char *isIsomorphic, int worldSize)
{
    bool result = true;
    for (int i = 0; i < worldSize; i++)
    {
        if (isIsomorphic[i] == 0)
        {
            result = false;
            break;
        }
    }
    return result;
}

void FindTransferBuckets(GraphData* buckets, long* bucketSumsSorted, int* bucketLoc, int numBuckets, long minLoad, long targetLoad, std::vector<int>& bucketsToTransfer, int vertexCount, int worldSize, int iteration)
{
    long currentLoad = minLoad;
    int currentBucket = 0;
    int numTries = 0;
    long totalIntsSoFar = 0;
    double factor = 1.0;
    double dampingFactor = 0.15;
    double exponent = iteration + 1;
    dampingFactor = pow(dampingFactor, exponent);
    const int MAX_INTS_TO_TRANSFER = 700000000; 
    while(currentLoad < (long)(targetLoad * (factor + dampingFactor)) && 
          numTries < numBuckets &&
          totalIntsSoFar < MAX_INTS_TO_TRANSFER)
    {
        if(bucketSumsSorted[currentBucket] > 0)
        {
            if(currentLoad + bucketSumsSorted[currentBucket] <= (long)(targetLoad * (factor + dampingFactor)) &&
               totalIntsSoFar + (buckets[bucketLoc[currentBucket]].TotalGraphSize() * (long)vertexCount) <= MAX_INTS_TO_TRANSFER)
            {
                currentLoad += bucketSumsSorted[currentBucket];
                totalIntsSoFar += (buckets[bucketLoc[currentBucket]].TotalGraphSize() * (long)vertexCount);
                bucketsToTransfer.push_back(bucketLoc[currentBucket]);
            }
        }
        currentBucket++;
        numTries++;
        if(currentBucket > numBuckets - 1)
        {
            currentBucket = 0;
        }
    }
}

long CalculateBucketWork(GraphData* graphBucket)
{
    long work = 0;
    long rowsSizeForBucket = graphBucket->rows1.size() + graphBucket->rows2.size();  
    if(rowsSizeForBucket > 1)
    {
        work += rowsSizeForBucket * (rowsSizeForBucket - 1)/2;
    }
    long colsSizeForBucket = graphBucket->cols1.size() + graphBucket->cols2.size();
    if(colsSizeForBucket > 1)
    {
        work += colsSizeForBucket * (colsSizeForBucket - 1)/2;
    }
    return work;
}

SendRecvRoleResult FindSendRecvRole(GraphData* graphDataBuckets, int numBuckets, int worldSize, int rank, int vertexCount)
{
    SendRecvRoleResult result;
    long work = 0;
    for(int i = 0; i < numBuckets; i++)
    {
        work += CalculateBucketWork(graphDataBuckets + i);
    }
    long mySendCounts = work;
    long* recv_sizes = new long[worldSize];
    long* recvSizesSorted = new long[worldSize];
    int* recvSizesLoc = new int[worldSize];
    std::vector<long> send_sizes(worldSize, mySendCounts);
    MPI_Allgather(&mySendCounts, 1, MPI_LONG, recv_sizes, 1, MPI_LONG, MPI_COMM_WORLD);
    for(int i = 0; i < worldSize; i++)
    {
        recvSizesLoc[i] = i;
    }
    memcpy(recvSizesSorted, recv_sizes, worldSize * sizeof(long));
    #ifdef INTERNODE_LOAD_BALANCE_DEBUG
    OUT << "Global load in rows1 + cols1 + rows2 + cols2" << std::endl;
    for(int i = 0; i < worldSize; i++)
    {
        OUT << recv_sizes[i] << " ";
    }
    OUT << std::endl << std::endl;
    #endif

    sortValueAndLocationPair(recvSizesSorted, recvSizesLoc, worldSize);
    #ifdef INTERNODE_LOAD_BALANCE_DEBUG
    OUT << "Global load in rows1 + cols1 + rows2 + cols2 Sorted" << std::endl;
    for(int i = 0; i < worldSize; i++)
    {
        OUT << recvSizesSorted[i] << " ";
    }
    OUT << std::endl << std::endl;
    OUT << "Global load in rows1 + cols1 + rows2 + cols2 Locs" << std::endl;
    for(int i = 0; i < worldSize; i++)
    {
        OUT << recvSizesLoc[i] << " ";
    }
    OUT << std::endl << std::endl;
    #endif

    int maxLoadIndex = worldSize - 1;
    int minLoadIndex = 0;
    int maxRank = -1;
    int minRank = 9999999;
    long minLoad = 999999999999999;
    long maxLoad = -1;
    while(maxLoadIndex > minLoadIndex)
    {
        maxRank = recvSizesLoc[maxLoadIndex];
        if(maxRank == rank)
        {
            minRank = recvSizesLoc[minLoadIndex];
            minLoad = recvSizesSorted[minLoadIndex];
            maxLoad = recvSizesSorted[maxLoadIndex];
            if(maxLoad == 0)
            {
                maxRank = -1;
                minRank = 9999999;
                minLoad = 999999999999999;
                maxLoad = -1;
            }
            break;
        }
        minRank = recvSizesLoc[minLoadIndex];
        if(minRank == rank)
        {
            maxRank = recvSizesLoc[maxLoadIndex];
            minLoad = recvSizesSorted[minLoadIndex];
            maxLoad = recvSizesSorted[maxLoadIndex];
            if(maxLoad == 0)
            {
                maxRank = -1;
                minRank = 9999999;
                minLoad = 999999999999999;
                maxLoad = -1;
            }
            break;
        }   
        maxLoadIndex--;
        minLoadIndex++;
    }
    result.maxRank = maxRank;
    result.minRank = minRank;
    result.minLoad = minLoad;
    result.maxLoad = maxLoad;
    result.maxImbalance = recvSizesSorted[worldSize-1] - recvSizesSorted[0];
    
    long target = 0;
    long totalWork = 0;
    for(int i = 0; i < worldSize; i++)
    {
        totalWork += recvSizesSorted[i];
    }
    target = totalWork/worldSize;
    long totalDeviation = 0;
    for(int i = 0; i < worldSize; i++)
    {
        totalDeviation += abs(recvSizesSorted[i] - target);
    }
    result.meanAbsDeviation = totalDeviation/worldSize;
    result.targetLoad = target;
    delete[] recv_sizes;
    delete[] recvSizesSorted;
    delete[] recvSizesLoc;
    return result;
}
InternodeBalanceResult InternodeLoadBalanceIteration(GraphData* graphData, int vertexCount, int rank, int worldSize, int iteration)
{
    InternodeBalanceResult result;
    result.newGraph = graphData;
    const int NUM_BUCKETS = 100;
    const int MAX_MSG_SIZE = 500000000;
    long *rowCounts1 = new long[vertexCount];
    long *colCounts1 = new long[vertexCount];
    long *rowCounts2 = new long[vertexCount];
    long *colCounts2 = new long[vertexCount];

    long *rowXor1 = new long[vertexCount];
    long *colXor1 = new long[vertexCount];
    long *rowXor2 = new long[vertexCount];
    long *colXor2 = new long[vertexCount];
    GraphData* buckets = new GraphData[NUM_BUCKETS];
    long *bucketSums = new long[NUM_BUCKETS];
    long *bucketSumsSorted = new long[NUM_BUCKETS];
    int *bucketLoc = new int[NUM_BUCKETS];
    #pragma omp parallel
    {
        #pragma omp for
        for (int k = 0; k < vertexCount; k++)
        {
            rowCounts1[k] = 0;
            colCounts1[k] = 0;
            rowCounts2[k] = 0;
            colCounts2[k] = 0;
            rowXor1[k] = 0;
            colXor1[k] = 0;
            rowXor2[k] = 0;
            colXor2[k] = 0;
        }
        #pragma omp for
        for(int i = 0; i < graphData->rows1.size(); i++)
        {
            for(int j = 0; j < vertexCount; j++)
            {
                rowCounts1[graphData->rows1[i]] += graphData->colors1CurrentRows[i * vertexCount + j];
                rowXor1[graphData->rows1[i]] ^= graphData->colors1CurrentRows[i * vertexCount + j];
            }
        }
        #pragma omp for
        for(int i = 0; i < graphData->rows2.size(); i++)
        {
            for(int j = 0; j < vertexCount; j++)
            {
                rowCounts2[graphData->rows2[i]] += graphData->colors2CurrentRows[i * vertexCount + j];
                rowXor2[graphData->rows2[i]] ^= graphData->colors2CurrentRows[i * vertexCount + j];
            }
        }
        #pragma omp for
        for(int i = 0; i < graphData->cols1.size(); i++)
        {
            for(int j = 0; j < vertexCount; j++)
            {
                colCounts1[graphData->cols1[i]] += graphData->colors1CurrentCols[i * vertexCount + j];
                colXor1[graphData->cols1[i]] ^= graphData->colors1CurrentCols[i * vertexCount + j];
            }
        }
        #pragma omp for
        for(int i = 0; i < graphData->cols2.size(); i++)
        {
            for(int j = 0; j < vertexCount; j++)
            {
                colCounts2[graphData->cols2[i]] += graphData->colors2CurrentCols[i * vertexCount + j];
                colXor2[graphData->cols2[i]] ^= graphData->colors2CurrentCols[i * vertexCount + j];
            }
        }
    }
    
    #ifdef SUMS_AND_XORS_FOR_NODE_BALANCE
    std::cout << "row counts 1: " << std::endl;
    for (int k = 0; k < vertexCount; k++)
    {
        std::cout << rowCounts1[k] << " ";
    }
    std::cout << std::endl;
    std::cout << "col counts 1: " << std::endl;
    for (int k = 0; k < vertexCount; k++)
    {
        std::cout << colCounts1[k] << " ";
    }
    std::cout << std::endl;
    std::cout << "row counts 2: " << std::endl;
    for (int k = 0; k < vertexCount; k++)
    {
        std::cout << rowCounts2[k] << " ";
    }
    std::cout << std::endl;
    std::cout << "col counts 2: " << std::endl;
    for (int k = 0; k < vertexCount; k++)
    {
        std::cout << colCounts2[k] << " ";
    }
    std::cout << std::endl;
    std::cout << "row xor 1: " << std::endl;
    for (int k = 0; k < vertexCount; k++)
    {
        std::cout << rowXor1[k] << " ";
    }
    std::cout << std::endl;
    std::cout << "col xor 1: " << std::endl;
    for (int k = 0; k < vertexCount; k++)
    {
        std::cout << colXor1[k] << " ";
    }
    std::cout << std::endl;
    std::cout << "row xor 2: " << std::endl;
    for (int k = 0; k < vertexCount; k++)
    {
        std::cout << rowXor2[k] << " ";
    }
    std::cout << std::endl;
    std::cout << "col xor 2: " << std::endl;
    for (int k = 0; k < vertexCount; k++)
    {
        std::cout << colXor2[k] << " ";
    }
    std::cout << std::endl;
    #endif
    #pragma omp parallel
    {
        #pragma omp sections
        {
            #pragma omp section
            {
                for(int i = 0; i < graphData->rows1.size(); i++)
                {
                    int bucket = ((rowCounts1[graphData->rows1[i]] & 0xAAAAAAAAAAAAAAAA) | (rowXor1[graphData->rows1[i]] & 0x5555555555555555)) % 100;
                    buckets[bucket].rows1.push_back(graphData->rows1[i]);
                }
            }
            #pragma omp section
            {
                for(int i = 0; i < graphData->rows2.size(); i++)
                {
                    int bucket = ((rowCounts2[graphData->rows2[i]] & 0xAAAAAAAAAAAAAAAA) | (rowXor2[graphData->rows2[i]] & 0x5555555555555555)) % 100;
                    buckets[bucket].rows2.push_back(graphData->rows2[i]);
                }
            }
            #pragma omp section
            {
                for(int i = 0; i < graphData->cols1.size(); i++)
                {
                    int bucket = ((colCounts1[graphData->cols1[i]] & 0xAAAAAAAAAAAAAAAA) | (colXor1[graphData->cols1[i]] & 0x5555555555555555)) % 100;
                    buckets[bucket].cols1.push_back(graphData->cols1[i]);
                }
            }
            #pragma omp section
            {
                for(int i = 0; i < graphData->cols2.size(); i++)
                {
                    int bucket = ((colCounts2[graphData->cols2[i]] & 0xAAAAAAAAAAAAAAAA) | (colXor2[graphData->cols2[i]] & 0x5555555555555555)) % 100;
                    buckets[bucket].cols2.push_back(graphData->cols2[i]);
                }
            }
        }
        for(int k = 0; k < NUM_BUCKETS; k++)
        {
            bucketLoc[k] = k;
            bucketSums[k] = CalculateBucketWork(buckets + k);
        }
    }
    
    SendRecvRoleResult sendRecvResult = FindSendRecvRole(buckets, NUM_BUCKETS, worldSize, rank, vertexCount);
    #ifdef INTERNODE_LOAD_BALANCE_DEBUG
    OUT << "Iteration: " << iteration << std::endl;
    OUT << "Rank " << rank << " send/recv result, send: " << sendRecvResult.maxRank << " recv: " << sendRecvResult.minRank << std::endl;
    #endif
    if(iteration < 11)
    {
        if(rank == sendRecvResult.maxRank)
        {
            #ifdef INTERNODE_LOAD_BALANCE_DEBUG
            OUT << "Rank " << rank << " sending." << std::endl;
            #endif
            
            memcpy(bucketSumsSorted, bucketSums, NUM_BUCKETS * sizeof(long));
            sortValueAndLocationPair(bucketSumsSorted, bucketLoc, NUM_BUCKETS);
            #ifdef INTERNODE_LOAD_BALANCE_DEBUG
            for(int k = 0; k < NUM_BUCKETS; k++)
            {
                 OUT << "Bucket sorted" << k << ": " << bucketSumsSorted[k] << std::endl;
            }
            for(int k = 0; k < NUM_BUCKETS; k++)
            {
                 OUT << bucketLoc[k] << " " << std::endl;
            }
            #endif
            std::vector<int> bucketsToTransfer;
            FindTransferBuckets(buckets, bucketSumsSorted, bucketLoc, NUM_BUCKETS, sendRecvResult.minLoad, sendRecvResult.targetLoad, bucketsToTransfer, vertexCount, worldSize, iteration);
            #ifdef INTERNODE_LOAD_BALANCE_DEBUG
            OUT << "Buckets to transfer: " << std::endl;
            #endif
            std::set<int> rows1_set;
            std::set<int> rows2_set;
            std::set<int> cols1_set;
            std::set<int> cols2_set;
            for(int i = 0; i < bucketsToTransfer.size(); i++)
            {
                #ifdef INTERNODE_LOAD_BALANCE_DEBUG
                OUT << bucketsToTransfer[i] << std::endl;
                #endif   
                rows1_set.insert(buckets[bucketsToTransfer[i]].rows1.begin(), 
                            buckets[bucketsToTransfer[i]].rows1.end());
                rows2_set.insert(buckets[bucketsToTransfer[i]].rows2.begin(), 
                            buckets[bucketsToTransfer[i]].rows2.end());
                cols1_set.insert(buckets[bucketsToTransfer[i]].cols1.begin(), 
                            buckets[bucketsToTransfer[i]].cols1.end());
                cols2_set.insert(buckets[bucketsToTransfer[i]].cols2.begin(), 
                            buckets[bucketsToTransfer[i]].cols2.end());
            }
            long buffSize = 4 + 
                        rows1_set.size() + 
                        rows2_set.size() + 
                        cols1_set.size() + 
                        cols2_set.size() +
                        (rows1_set.size() * vertexCount) +
                        (rows2_set.size() * vertexCount) +
                        (cols1_set.size() * vertexCount) +
                        (cols2_set.size() * vertexCount);
            int numMsg = buffSize/MAX_MSG_SIZE;
            int lastMsgSize = buffSize % MAX_MSG_SIZE; 
            if(lastMsgSize != 0)
            {
                numMsg++;
            }
            int* sendBuffer = new int[buffSize];
            int* msgInfoBuffer = new int[numMsg + 1];
            int msgInfoPtr = 0;
            msgInfoBuffer[msgInfoPtr] = numMsg;
            msgInfoPtr++;
            for(int i = 0; i < numMsg; i++)
            {
                if(i + 1 == numMsg)
                {
                    msgInfoBuffer[msgInfoPtr] = lastMsgSize;
                }
                else
                {
                    msgInfoBuffer[msgInfoPtr] = MAX_MSG_SIZE;
                }
                msgInfoPtr++;
            }
            int currPtr = 0;
            sendBuffer[currPtr] = rows1_set.size();
            currPtr++;
            sendBuffer[currPtr] = rows2_set.size();
            currPtr++;
            sendBuffer[currPtr] = cols1_set.size();
            currPtr++;
            sendBuffer[currPtr] = cols2_set.size();
            currPtr++;
            std::vector<int> rows1_vec(rows1_set.begin(), rows1_set.end());
            std::sort(rows1_vec.begin(), rows1_vec.end());
            std::vector<int> rows2_vec(rows2_set.begin(), rows2_set.end());
            std::sort(rows2_vec.begin(), rows2_vec.end());
            std::vector<int> cols1_vec(cols1_set.begin(), cols1_set.end());
            std::sort(cols1_vec.begin(), cols1_vec.end());
            std::vector<int> cols2_vec(cols2_set.begin(), cols2_set.end());
            std::sort(cols2_vec.begin(), cols2_vec.end());
            for(int i = 0; i < rows1_vec.size(); i++)
            {
                sendBuffer[currPtr] = rows1_vec[i];
                currPtr++;
            }
            for(int i = 0; i < rows2_vec.size(); i++)
            {
                sendBuffer[currPtr] = rows2_vec[i];
                currPtr++;
            }
            for(int i = 0; i < cols1_vec.size(); i++)
            {
                sendBuffer[currPtr] = cols1_vec[i];
                currPtr++;
            }
            for(int i = 0; i < cols2_vec.size(); i++)
            {
                sendBuffer[currPtr] = cols2_vec[i];
                currPtr++;
            }
            for(int i = 0; i < graphData->rows1.size(); i++)
            {
                if(rows1_set.find(graphData->rows1[i]) != rows1_set.end())
                {
                    for(int j = 0; j < vertexCount; j++)
                    {
                        sendBuffer[currPtr] = graphData->colors1CurrentRows[i * vertexCount + j];
                        currPtr++;
                    }
                }
            }
            for(int i = 0; i < graphData->rows2.size(); i++)
            {
                if(rows2_set.find(graphData->rows2[i]) != rows2_set.end())
                {
                    for(int j = 0; j < vertexCount; j++)
                    {
                        sendBuffer[currPtr] = graphData->colors2CurrentRows[i * vertexCount + j];
                        currPtr++;
                    }
                }
            }
            for(int i = 0; i < graphData->cols1.size(); i++)
            {
                if(cols1_set.find(graphData->cols1[i]) != cols1_set.end())
                {
                    for(int j = 0; j < vertexCount; j++)
                    {
                        sendBuffer[currPtr] = graphData->colors1CurrentCols[i * vertexCount + j];
                        currPtr++;
                    }
                }
            }
            for(int i = 0; i < graphData->cols2.size(); i++)
            {
                if(cols2_set.find(graphData->cols2[i]) != cols2_set.end())
                {
                    for(int j = 0; j < vertexCount; j++)
                    {
                        sendBuffer[currPtr] = graphData->colors2CurrentCols[i * vertexCount + j];
                        currPtr++;
                    }
                }
            }
            #ifdef INTERNODE_LOAD_BALANCE_DEBUG
            OUT << "sending " << numMsg + 1  << " integers for message metadata" << std::endl;
            #endif
            if (MPI_Send(msgInfoBuffer, numMsg + 1, MPI_INT, sendRecvResult.minRank, 0, MPI_COMM_WORLD) != MPI_SUCCESS)
            {
                #ifdef INTERNODE_LOAD_BALANCE_DEBUG
                    OUT << "Rank " << rank << " had a problem sending message metadata."
                                << std::endl;
                #endif
            }
            int msgCount = msgInfoBuffer[0];
            int sendBufferOffset = 0;
            for(int i = 0; i < msgCount; i++)
            {
                int size = msgInfoBuffer[i+1];
                #ifdef INTERNODE_LOAD_BALANCE_DEBUG 
                OUT << "sending " << size  << " integers." << std::endl;
                #endif
                if (MPI_Send(sendBuffer + sendBufferOffset, size, MPI_INT, sendRecvResult.minRank, 0, MPI_COMM_WORLD) != MPI_SUCCESS)
                {
                    #ifdef INTERNODE_LOAD_BALANCE_DEBUG
                        OUT << "Rank " << rank << " had a problem sending load balance data."
                                    << std::endl;
                    #endif
                }
                sendBufferOffset += size;
            }
            
            delete [] sendBuffer;
            delete [] msgInfoBuffer;
            GraphData* newGraphData = new GraphData();
            std::set_difference(graphData->rows1.begin(), 
                                graphData->rows1.end(), 
                                rows1_vec.begin(), 
                                rows1_vec.end(), 
                                std::inserter(newGraphData->rows1, newGraphData->rows1.begin()));
            std::set_difference(graphData->rows2.begin(), 
                                graphData->rows2.end(), 
                                rows2_vec.begin(), 
                                rows2_vec.end(), 
                                std::inserter(newGraphData->rows2, newGraphData->rows2.begin()));
            std::set_difference(graphData->cols1.begin(), 
                                graphData->cols1.end(), 
                                cols1_vec.begin(), 
                                cols1_vec.end(), 
                                std::inserter(newGraphData->cols1, newGraphData->cols1.begin()));
            std::set_difference(graphData->cols2.begin(), 
                                graphData->cols2.end(), 
                                cols2_vec.begin(), 
                                cols2_vec.end(), 
                                std::inserter(newGraphData->cols2, newGraphData->cols2.begin()));
            InitializeGraphMemory(newGraphData, vertexCount);
            #pragma omp parallel
            {
                #pragma omp for
                for(int i = 0; i < newGraphData->rows1.size(); i++)
                {
                    int row = newGraphData->rows1[i];
                    auto it = std::lower_bound(graphData->rows1.begin(), graphData->rows1.end(), row);
                    int oldRowLocation = std::distance(graphData->rows1.begin(), it);
                    for(int j = 0; j < vertexCount; j++)
                    {
                        newGraphData->colors1CurrentRows[i * vertexCount + j] = graphData->colors1CurrentRows[oldRowLocation * vertexCount + j];
                    }
                }
                #pragma omp for
                for(int i = 0; i < newGraphData->rows2.size(); i++)
                {
                    int row = newGraphData->rows2[i];
                    auto it = std::lower_bound(graphData->rows2.begin(), graphData->rows2.end(), row);
                    int oldRowLocation = std::distance(graphData->rows2.begin(), it);
                    for(int j = 0; j < vertexCount; j++)
                    {
                        newGraphData->colors2CurrentRows[i * vertexCount + j] = graphData->colors2CurrentRows[oldRowLocation * vertexCount + j];
                    }
                }
                #pragma omp for
                for(int i = 0; i < newGraphData->cols1.size(); i++)
                {
                    int col = newGraphData->cols1[i];
                    auto it = std::lower_bound(graphData->cols1.begin(), graphData->cols1.end(), col);
                    int oldColLocation = std::distance(graphData->cols1.begin(), it);
                    for(int j = 0; j < vertexCount; j++)
                    {
                        newGraphData->colors1CurrentCols[i * vertexCount + j] = graphData->colors1CurrentCols[oldColLocation * vertexCount + j];
                    }
                }
                #pragma omp for
                for(int i = 0; i < newGraphData->cols2.size(); i++)
                {
                    int col = newGraphData->cols2[i];
                    auto it = std::lower_bound(graphData->cols2.begin(), graphData->cols2.end(), col);
                    int oldColLocation = std::distance(graphData->cols2.begin(), it);
                    for(int j = 0; j < vertexCount; j++)
                    {
                        newGraphData->colors2CurrentCols[i * vertexCount + j] = graphData->colors2CurrentCols[oldColLocation * vertexCount + j];
                    }
                }
            }
            delete graphData;
            result.newGraph = newGraphData;
        }
        if(rank == sendRecvResult.minRank)
        {
            #ifdef INTERNODE_LOAD_BALANCE_DEBUG
            OUT << "Rank " << rank << " receiving. from " << sendRecvResult.maxRank << std::endl;
            #endif
            MPI_Status status;
            MPI_Status probeStatus;
            if(MPI_Probe(MPI_ANY_SOURCE, 0, MPI_COMM_WORLD, &probeStatus) != MPI_SUCCESS)
            {
                OUT << "Rank " << rank << " had a problem with MPI Probe" << std::endl;
            }
            MPI_Count number;
            MPI_Get_elements_x(&probeStatus, MPI_INT, &number);
    #ifdef INTERNODE_LOAD_BALANCE_DEBUG
            std::cout << "The count for the message metadata is " << number << std::endl;
    #endif
            int* msgInfoBuffer = new int[number];
    #ifdef INTERNODE_LOAD_BALANCE_DEBUG
            std::cout << "Rank " << rank << " starting receive rows1 from rank "
                    << probeStatus.MPI_SOURCE << "." << std::endl;
    #endif
            if (MPI_Recv(msgInfoBuffer, number, MPI_INT, probeStatus.MPI_SOURCE, 0, MPI_COMM_WORLD, &status) != MPI_SUCCESS)
            {
    #ifdef INTERNODE_LOAD_BALANCE_DEBUG
                std::cout << "Rank " << rank
                        << " had a problem receiving rows1 from rank "
                        << probeStatus.MPI_SOURCE << "." << std::endl;
    #endif
            }
            MPI_Count count;
            MPI_Get_elements_x(&status, MPI_INT, &count);
    #ifdef INTERNODE_LOAD_BALANCE_DEBUG
            std::cout << "The count for rank " << rank << " from rank "
                << probeStatus.MPI_SOURCE << " is " << count << std::endl;
    #endif
            int numMsg = msgInfoBuffer[0];
            long totalRecvSize = 0;
            for(int i = 0; i < numMsg; i++)
            {
                totalRecvSize += msgInfoBuffer[i + 1]; 
            }
            int* receivedData = new int[totalRecvSize];
            int totalCountRecv = 0;
            int recvOffset = 0;
            for(int i = 0; i < numMsg; i++)
            {
                int size = msgInfoBuffer[i + 1]; 
                MPI_Status status;
                MPI_Status probeStatus;
                if(MPI_Probe(MPI_ANY_SOURCE, 0, MPI_COMM_WORLD, &probeStatus) != MPI_SUCCESS)
                {
                    OUT << "Rank " << rank << " had a problem with MPI Probe" << std::endl;
                }
                MPI_Count number;
                MPI_Get_elements_x(&probeStatus, MPI_INT, &number);
        #ifdef INTERNODE_LOAD_BALANCE_DEBUG
                std::cout << "The count for the load balance message is " << number << std::endl;
        #endif
        #ifdef INTERNODE_LOAD_BALANCE_DEBUG
                std::cout << "Rank " << rank << " starting receive rows1 from rank "
                        << probeStatus.MPI_SOURCE << "." << std::endl;
        #endif
                if (MPI_Recv(receivedData + recvOffset, number, MPI_INT, probeStatus.MPI_SOURCE, 0, MPI_COMM_WORLD, &status) != MPI_SUCCESS)
                {
        #ifdef INTERNODE_LOAD_BALANCE_DEBUG
                    std::cout << "Rank " << rank
                            << " had a problem receiving rows1 from rank "
                            << probeStatus.MPI_SOURCE << "." << std::endl;
        #endif
                }
                MPI_Count count;
                MPI_Get_elements_x(&status, MPI_INT, &count);
                totalCountRecv += count;
        #ifdef INTERNODE_LOAD_BALANCE_DEBUG
                std::cout << "The count for rank " << rank << " from rank "
                    << probeStatus.MPI_SOURCE << " is " << count << std::endl;
        #endif
                recvOffset += size;
            }
            if (totalCountRecv != 1)
            {
                #ifdef INTERNODE_LOAD_BALANCE_DEBUG
                OUT << "Rank " << rank << " received " << count << std::endl;
                #endif
                int rows1Size = receivedData[0];
                int rows2Size = receivedData[1];
                int cols1Size = receivedData[2];
                int cols2Size = receivedData[3];
                #ifdef INTERNODE_LOAD_BALANCE_DEBUG
                OUT << "rows1:" << rows1Size << " rows2: " << rows2Size << "cols1: " << cols1Size << "cols2: " << cols2Size << count << std::endl;
                #endif
                GraphData newGraph;
                int ptr = 4;
                for(int i = 0; i < rows1Size; i++)
                {
                    newGraph.rows1.push_back(receivedData[ptr]);
                    ptr++;
                }
                std::vector<int> combinedRows1Pos(graphData->rows1.begin(), graphData->rows1.end());
                combinedRows1Pos.insert(combinedRows1Pos.end(), newGraph.rows1.begin(), newGraph.rows1.end());
                std::sort(combinedRows1Pos.begin(), combinedRows1Pos.end());
                for(int i = 0; i < rows2Size; i++)
                {
                    newGraph.rows2.push_back(receivedData[ptr]);
                    ptr++;
                }
                std::vector<int> combinedRows2Pos(graphData->rows2.begin(), graphData->rows2.end());
                combinedRows2Pos.insert(combinedRows2Pos.end(), newGraph.rows2.begin(), newGraph.rows2.end());
                std::sort(combinedRows2Pos.begin(), combinedRows2Pos.end());
                for(int i = 0; i < cols1Size; i++)
                {
                    newGraph.cols1.push_back(receivedData[ptr]);
                    ptr++;
                }
                std::vector<int> combinedCols1Pos(graphData->cols1.begin(), graphData->cols1.end());
                combinedCols1Pos.insert(combinedCols1Pos.end(), newGraph.cols1.begin(), newGraph.cols1.end());
                std::sort(combinedCols1Pos.begin(), combinedCols1Pos.end());
                for(int i = 0; i < cols2Size; i++)
                {
                    newGraph.cols2.push_back(receivedData[ptr]);
                    ptr++;
                }
                std::vector<int> combinedCols2Pos(graphData->cols2.begin(), graphData->cols2.end());
                combinedCols2Pos.insert(combinedCols2Pos.end(), newGraph.cols2.begin(), newGraph.cols2.end());
                std::sort(combinedCols2Pos.begin(), combinedCols2Pos.end());
                GraphData* combinedGraphData = new GraphData();
                combinedGraphData->cols1.insert(combinedGraphData->cols1.end(), combinedCols1Pos.begin(), combinedCols1Pos.end());
                combinedGraphData->cols2.insert(combinedGraphData->cols2.end(), combinedCols2Pos.begin(), combinedCols2Pos.end());
                combinedGraphData->rows1.insert(combinedGraphData->rows1.end(), combinedRows1Pos.begin(), combinedRows1Pos.end());
                combinedGraphData->rows2.insert(combinedGraphData->rows2.end(), combinedRows2Pos.begin(), combinedRows2Pos.end());
                InitializeGraphMemory(combinedGraphData, vertexCount);
                int rows1Ptr = ptr;
                int rows2Ptr = rows1Ptr + newGraph.rows1.size() * vertexCount;
                int cols1Ptr = rows2Ptr + newGraph.rows2.size() * vertexCount;
                int cols2Ptr = cols1Ptr + newGraph.cols1.size() * vertexCount;
                #pragma omp parallel
                {
                    #pragma omp for
                    for(int i = 0; i < newGraph.rows1.size(); i++)
                    {
                        int row = newGraph.rows1[i];
                        auto it = std::lower_bound(combinedGraphData->rows1.begin(), combinedGraphData->rows1.end(), row);
                        int newRowLocation = std::distance(combinedGraphData->rows1.begin(), it);
                        for(int j = 0; j < vertexCount; j++)
                        {
                            combinedGraphData->colors1CurrentRows[newRowLocation * vertexCount + j] = receivedData[rows1Ptr + (i * vertexCount) + j];
                        }
                    }
                    #pragma omp for
                    for(int i = 0; i < newGraph.rows2.size(); i++)
                    {
                        int row = newGraph.rows2[i];
                        auto it = std::lower_bound(combinedGraphData->rows2.begin(), combinedGraphData->rows2.end(), row);
                        int newRowLocation = std::distance(combinedGraphData->rows2.begin(), it);
                        for(int j = 0; j < vertexCount; j++)
                        {
                            combinedGraphData->colors2CurrentRows[newRowLocation * vertexCount + j] = receivedData[rows2Ptr + (i * vertexCount) + j];
                        }
                    }
                    #pragma omp for
                    for(int i = 0; i < newGraph.cols1.size(); i++)
                    {
                        int col = newGraph.cols1[i];
                        auto it = std::lower_bound(combinedGraphData->cols1.begin(), combinedGraphData->cols1.end(), col);
                        int newColLocation = std::distance(combinedGraphData->cols1.begin(), it);
                        for(int j = 0; j < vertexCount; j++)
                        {
                            combinedGraphData->colors1CurrentCols[newColLocation * vertexCount + j] = receivedData[cols1Ptr + (i * vertexCount) + j];
                        }
                    }
                    #pragma omp for
                    for(int i = 0; i < newGraph.cols2.size(); i++)
                    {
                        int col = newGraph.cols2[i];
                        auto it = std::lower_bound(combinedGraphData->cols2.begin(), combinedGraphData->cols2.end(), col);
                        int newColLocation = std::distance(combinedGraphData->cols2.begin(), it);
                        for(int j = 0; j < vertexCount; j++)
                        {
                            combinedGraphData->colors2CurrentCols[newColLocation * vertexCount + j] = receivedData[cols2Ptr + (i * vertexCount) + j];
                        }
                    }
                    #pragma omp for
                    for(int i = 0; i < graphData->rows1.size(); i++)
                    {
                        int row = graphData->rows1[i];
                        auto it = std::lower_bound(combinedGraphData->rows1.begin(), combinedGraphData->rows1.end(), row);
                        int newRowLocation = std::distance(combinedGraphData->rows1.begin(), it);
                        for(int j = 0; j < vertexCount; j++)
                        {
                            combinedGraphData->colors1CurrentRows[newRowLocation * vertexCount + j] = graphData->colors1CurrentRows[i * vertexCount + j];
                        }
                    }
                    #pragma omp for
                    for(int i = 0; i < graphData->rows2.size(); i++)
                    {
                        int row = graphData->rows2[i];
                        auto it = std::lower_bound(combinedGraphData->rows2.begin(), combinedGraphData->rows2.end(), row);
                        int newRowLocation = std::distance(combinedGraphData->rows2.begin(), it);
                        for(int j = 0; j < vertexCount; j++)
                        {
                            combinedGraphData->colors2CurrentRows[newRowLocation * vertexCount + j] = graphData->colors2CurrentRows[i * vertexCount + j];
                        }
                    }
                    #pragma omp for
                    for(int i = 0; i < graphData->cols1.size(); i++)
                    {
                        int col = graphData->cols1[i];
                        auto it = std::lower_bound(combinedGraphData->cols1.begin(), combinedGraphData->cols1.end(), col);
                        int newColLocation = std::distance(combinedGraphData->cols1.begin(), it);
                        for(int j = 0; j < vertexCount; j++)
                        {
                            combinedGraphData->colors1CurrentCols[newColLocation * vertexCount + j] = graphData->colors1CurrentCols[i * vertexCount + j];
                        }
                    }
                    #pragma omp for
                    for(int i = 0; i < graphData->cols2.size(); i++)
                    {
                        int col = graphData->cols2[i];
                        auto it = std::lower_bound(combinedGraphData->cols2.begin(), combinedGraphData->cols2.end(), col);
                        int newColLocation = std::distance(combinedGraphData->cols2.begin(), it);
                        for(int j = 0; j < vertexCount; j++)
                        {
                            combinedGraphData->colors2CurrentCols[newColLocation * vertexCount + j] = graphData->colors2CurrentCols[i * vertexCount + j];
                        }
                    }
                }
                delete graphData;
                result.newGraph = combinedGraphData;
            }
            delete[] receivedData;
            delete[] msgInfoBuffer;
        }
    }
    #pragma omp parallel
    {
        #pragma omp for
        for (int k = 0; k < vertexCount; k++)
        {
            rowCounts1[k] = 0;
            colCounts1[k] = 0;
            rowCounts2[k] = 0;
            colCounts2[k] = 0;
            rowXor1[k] = 0;
            colXor1[k] = 0;
            rowXor2[k] = 0;
            colXor2[k] = 0;
        }
        #pragma omp for
        for(int i = 0; i < result.newGraph->rows1.size(); i++)
        {
            for(int j = 0; j < vertexCount; j++)
            {
                rowCounts1[result.newGraph->rows1[i]] += result.newGraph->colors1CurrentRows[i * vertexCount + j];
                rowXor1[result.newGraph->rows1[i]] ^= result.newGraph->colors1CurrentRows[i * vertexCount + j];
            }
        }
        #pragma omp for
        for(int i = 0; i < result.newGraph->rows2.size(); i++)
        {
            for(int j = 0; j < vertexCount; j++)
            {
                rowCounts2[result.newGraph->rows2[i]] += result.newGraph->colors2CurrentRows[i * vertexCount + j];
                rowXor2[result.newGraph->rows2[i]] ^= result.newGraph->colors2CurrentRows[i * vertexCount + j];
            }
        }
        #pragma omp for
        for(int i = 0; i < result.newGraph->cols1.size(); i++)
        {
            for(int j = 0; j < vertexCount; j++)
            {
                colCounts1[result.newGraph->cols1[i]] += result.newGraph->colors1CurrentCols[i * vertexCount + j];
                colXor1[result.newGraph->cols1[i]] ^= result.newGraph->colors1CurrentCols[i * vertexCount + j];
            }
        }
        #pragma omp for
        for(int i = 0; i < result.newGraph->cols2.size(); i++)
        {
            for(int j = 0; j < vertexCount; j++)
            {
                colCounts2[result.newGraph->cols2[i]] += result.newGraph->colors2CurrentCols[i * vertexCount + j];
                colXor2[result.newGraph->cols2[i]] ^= result.newGraph->colors2CurrentCols[i * vertexCount + j];
            }
        }
        #ifdef SUMS_AND_XORS_FOR_NODE_BALANCE
        std::cout << "row counts 1: " << std::endl;
        for (int k = 0; k < vertexCount; k++)
        {
            std::cout << rowCounts1[k] << " ";
        }
        std::cout << std::endl;
        std::cout << "col counts 1: " << std::endl;
        for (int k = 0; k < vertexCount; k++)
        {
            std::cout << colCounts1[k] << " ";
        }
        std::cout << std::endl;
        std::cout << "row counts 2: " << std::endl;
        for (int k = 0; k < vertexCount; k++)
        {
            std::cout << rowCounts2[k] << " ";
        }
        std::cout << std::endl;
        std::cout << "col counts 2: " << std::endl;
        for (int k = 0; k < vertexCount; k++)
        {
            std::cout << colCounts2[k] << " ";
        }
        std::cout << std::endl;
        std::cout << "row xor 1: " << std::endl;
        for (int k = 0; k < vertexCount; k++)
        {
            std::cout << rowXor1[k] << " ";
        }
        std::cout << std::endl;
        std::cout << "col xor 1: " << std::endl;
        for (int k = 0; k < vertexCount; k++)
        {
            std::cout << colXor1[k] << " ";
        }
        std::cout << std::endl;
        std::cout << "row xor 2: " << std::endl;
        for (int k = 0; k < vertexCount; k++)
        {
            std::cout << rowXor2[k] << " ";
        }
        std::cout << std::endl;
        std::cout << "col xor 2: " << std::endl;
        for (int k = 0; k < vertexCount; k++)
        {
            std::cout << colXor2[k] << " ";
        }
        std::cout << std::endl;
        #endif
        #pragma omp for
        for(int i = 0; i < NUM_BUCKETS; i++)
        {
            buckets[i].rows1.clear();
            buckets[i].rows2.clear();
            buckets[i].cols1.clear();
            buckets[i].cols2.clear();
        }
        #pragma omp sections
        {
            #pragma omp section
            {
                for(int i = 0; i < result.newGraph->rows1.size(); i++)
                {
                    int bucket = ((rowCounts1[result.newGraph->rows1[i]] & 0xAAAAAAAAAAAAAAAA) | (rowXor1[result.newGraph->rows1[i]] & 0x5555555555555555)) % 100;
                    buckets[bucket].rows1.push_back(result.newGraph->rows1[i]);
                }
            }
            #pragma omp section
            {
                for(int i = 0; i < result.newGraph->rows2.size(); i++)
                {
                    int bucket = ((rowCounts2[result.newGraph->rows2[i]] & 0xAAAAAAAAAAAAAAAA) | (rowXor2[result.newGraph->rows2[i]] & 0x5555555555555555)) % 100;
                    buckets[bucket].rows2.push_back(result.newGraph->rows2[i]);
                }        
            }
            #pragma omp section
            {
                for(int i = 0; i < result.newGraph->cols1.size(); i++)
                {
                    int bucket = ((colCounts1[result.newGraph->cols1[i]] & 0xAAAAAAAAAAAAAAAA) | (colXor1[result.newGraph->cols1[i]] & 0x5555555555555555)) % 100;
                    buckets[bucket].cols1.push_back(result.newGraph->cols1[i]);
                }
            }
            #pragma omp section
            {
                for(int i = 0; i < result.newGraph->cols2.size(); i++)
                {
                    int bucket = ((colCounts2[result.newGraph->cols2[i]] & 0xAAAAAAAAAAAAAAAA) | (colXor2[result.newGraph->cols2[i]] & 0x5555555555555555)) % 100;
                    buckets[bucket].cols2.push_back(result.newGraph->cols2[i]);
                }
            }
        }
    }
    
    sendRecvResult = FindSendRecvRole(buckets, NUM_BUCKETS, worldSize, rank, vertexCount);
    result.minMaxDelta = sendRecvResult.maxImbalance;
    result.minLoad = sendRecvResult.minLoad == -1? 0: sendRecvResult.minLoad;
    result.maxLoad = sendRecvResult.maxLoad == -1? 0: sendRecvResult.maxLoad;
    result.targetLoad = sendRecvResult.targetLoad;

    delete[] bucketSums;
    delete[] bucketSumsSorted;
    delete[] bucketLoc;
    delete[] rowCounts1;
    delete[] colCounts1;
    delete[] rowCounts2;
    delete[] colCounts2;
    delete[] rowXor1;
    delete[] rowXor2;
    delete[] colXor1;
    delete[] colXor2;
    delete[] buckets;
    return result;
}

GraphData* InternodeLoadBalance(GraphData* graphData, int vertexCount, int rank, int worldSize, int iteration, long& minMaxLoadDelta, long& averageAbsDeviation, long& prevTargetLoad)
{
    if(worldSize > 1)
    {
        InternodeBalanceResult loadBalanceResult;
        long prevMinMaxDelta = 999999999999999;
        long prevAverageAbsDeviation = 999999999999999;
        loadBalanceResult.minMaxDelta = prevMinMaxDelta;
        loadBalanceResult.averageAbsDeviation = prevAverageAbsDeviation;
        long minMaxDeltaOffTargetLoad = (prevTargetLoad * 0.40);
        long meanAbsDeviationOffTargetLoad = (long)(prevTargetLoad * 0.35);
        while((minMaxLoadDelta < prevMinMaxDelta | averageAbsDeviation < prevAverageAbsDeviation) && 
            ((minMaxLoadDelta > minMaxDeltaOffTargetLoad) | (averageAbsDeviation > meanAbsDeviationOffTargetLoad)))
        {
            #ifdef INTERNODE_LOAD_BALANCE_DEBUG
            OUT << "min max delta: " << minMaxLoadDelta << " target delta: " << minMaxDeltaOffTargetLoad << std::endl;
            #endif
            prevMinMaxDelta = loadBalanceResult.minMaxDelta;
            prevAverageAbsDeviation = loadBalanceResult.averageAbsDeviation;
            loadBalanceResult = InternodeLoadBalanceIteration(graphData, vertexCount, rank, worldSize, iteration);
            graphData = loadBalanceResult.newGraph;
            averageAbsDeviation = loadBalanceResult.averageAbsDeviation;
            minMaxLoadDelta = loadBalanceResult.minMaxDelta;
            prevTargetLoad = loadBalanceResult.targetLoad;
            minMaxDeltaOffTargetLoad = (long)(prevTargetLoad * 0.40);
            meanAbsDeviationOffTargetLoad = (long)(prevTargetLoad * 0.35);
        }
    }
    return graphData;
}

void PrintGraph(GraphData* graphData, int vertexCount)
{
    for(int i = 0; i < graphData->rows1.size(); i++)
    {
        int rowNum = graphData->rows1[i];
        OUT << "g1 Row " << rowNum << ": ";
        for(int j = 0; j < vertexCount; j++)
        {
            OUT << graphData->colors1CurrentRows[i * vertexCount + j] << " ";
        }
        OUT << std::endl;
    }
    for(int i = 0; i < graphData->rows2.size(); i++)
    {
        int rowNum = graphData->rows2[i];
        OUT << "g2 Row " << rowNum << ": ";
        for(int j = 0; j < vertexCount; j++)
        {
            OUT << graphData->colors2CurrentRows[i * vertexCount + j] << " ";
        }
        OUT << std::endl;
    }
    for(int i = 0; i < graphData->cols1.size(); i++)
    {
        int colNum = graphData->cols1[i];
        OUT << "g1 Col " << colNum << ": ";
        for(int j = 0; j < vertexCount; j++)
        {
            OUT << graphData->colors1CurrentCols[i * vertexCount + j] << " ";
        }
        OUT << std::endl;
    }
    for(int i = 0; i < graphData->cols2.size(); i++)
    {
        int colNum = graphData->cols2[i];
        OUT << "g2 Col " << colNum << ": ";
        for(int j = 0; j < vertexCount; j++)
        {
            OUT << graphData->colors2CurrentCols[i * vertexCount + j] << " ";
        }
        OUT << std::endl;
    }
    OUT << std::endl << std::endl;
}
// --- BEGIN seeded-2WL patch (dialysis project, single-graph automorphism-orbit use case) ---
// Reads one integer per line (n lines, vertex i's seed colour on line i) -- the FVS-derived orbit
// label from the Kotlin side. Not part of upstream ScaWL.
int *loadSeedColours(const char *path, int vertexCount)
{
    int *seed = new int[vertexCount];
    std::ifstream f(path);
    for (int i = 0; i < vertexCount; i++)
    {
        f >> seed[i];
    }
    return seed;
}

// Adds seed[v] * BIG_OFFSET to EVERY entry of vertex v's row and column (both graph1 and graph2 --
// the two copies of the SAME graph in the self-comparison trick this patch uses). Applied ONCE,
// before the refinement loop starts: `RowsEqual` compares whole rows position-by-position, so two
// vertices with different seed colours can never compare equal at round 1 regardless of adjacency,
// and since round-1 colour ids are what every later round's signature is built from (see the
// `graphData->allRowColors1[originalVertexId] = colourValue` assignment later in this function),
// the seed distinction propagates through every subsequent round for free -- no need to re-inject
// it every iteration, same as seeding dialysis.refinement.colorRefine1WL's `initial` array once.
void applySeedOffset(GraphData *graphData, int *seedColours, int vertexCount)
{
    const long BIG_OFFSET = 1000000L;
    for (size_t i = 0; i < graphData->rows1.size(); i++)
    {
        long off = ((long)seedColours[graphData->rows1[i]]) * BIG_OFFSET;
        for (int j = 0; j < vertexCount; j++) graphData->colors1CurrentRows[i * vertexCount + j] += off;
    }
    for (size_t i = 0; i < graphData->cols1.size(); i++)
    {
        long off = ((long)seedColours[graphData->cols1[i]]) * BIG_OFFSET;
        for (int j = 0; j < vertexCount; j++) graphData->colors1CurrentCols[i * vertexCount + j] += off;
    }
    for (size_t i = 0; i < graphData->rows2.size(); i++)
    {
        long off = ((long)seedColours[graphData->rows2[i]]) * BIG_OFFSET;
        for (int j = 0; j < vertexCount; j++) graphData->colors2CurrentRows[i * vertexCount + j] += off;
    }
    for (size_t i = 0; i < graphData->cols2.size(); i++)
    {
        long off = ((long)seedColours[graphData->cols2[i]]) * BIG_OFFSET;
        for (int j = 0; j < vertexCount; j++) graphData->colors2CurrentCols[i * vertexCount + j] += off;
    }
}
// --- END seeded-2WL patch header ---

bool TwoWL(const char *g1f, const char *g2f, int vertexCount, int *g1, int *g2, int rank, int worldSize,
           int *seedColours = NULL, const char *outputPath = NULL)
{
    long totalBinWork = 0;
    int *maxAs = new int[worldSize];
    int *currentMaxColors = new int[worldSize];
    unsigned char *isIsomorphic = new unsigned char[worldSize];
    for (int i = 0; i < worldSize; i++)
    {
        maxAs[i] = -2;
        currentMaxColors[i] = -1;
        isIsomorphic[i] = 1;
    }
    #ifdef INIT_GRAPH_TIMES
    clock_t beforeInitGraph = clock() / (CLOCKS_PER_SEC / 1000);
    auto b_initGraph = std::chrono::high_resolution_clock::now();
    #endif
    GraphData *graphData;
    if (g1f != NULL)
    {
        graphData = initializeFromSparseLinear(g1f, g2f, vertexCount, rank, worldSize);
    }
    else
    {
        graphData = initializeFromDense(g1, g2, vertexCount, rank, worldSize);
    }
    #ifdef INIT_GRAPH_TIMES
    clock_t afterInitGraph = clock() / (CLOCKS_PER_SEC / 1000);
    auto after_initGraph = std::chrono::high_resolution_clock::now();
    std::cout << "graph load - cpu time " << to_string((double)(afterInitGraph - beforeInitGraph) / 1000.0) << std::endl;
    std::cout << "graph load - wall time " << getTimeDiff(b_initGraph, after_initGraph) << std::endl;   
    #endif
    bool isomorphic = true;
    long minMaxLoadDelta = 9999999999;
    long averageAbsDeviation = 9999999999;
    long prevTargetLoad = 0;
    graphData = InternodeLoadBalance(graphData, vertexCount, rank, worldSize, 0, minMaxLoadDelta, averageAbsDeviation, prevTargetLoad);
    if (seedColours != NULL)
    {
        applySeedOffset(graphData, seedColours, vertexCount);
    }
    initializeNextColors(graphData->colors1NextCols, graphData->cols1.size() * vertexCount);
    initializeNextColors(graphData->colors1NextRows, graphData->rows1.size() * vertexCount);
    initializeNextColors(graphData->colors2NextCols, graphData->cols2.size() * vertexCount);
    initializeNextColors(graphData->colors2NextRows, graphData->rows2.size() * vertexCount);

    // maxColor() on the raw (pre-hash1) arrays is only a valid proxy for "current colour count"
    // when those raw values ARE already small (0/1 adjacency, upstream's only intended use case).
    // The seed offset above makes raw values large by design (that's what makes RowsEqual respect
    // the seed) -- reading THAT through maxColor() as if it were a colour count made the pre-loop
    // baseline artificially huge, so the very first real hash1() round's genuine (small, properly
    // hashmap-assigned) colour count looked like a DECREASE and the while(prev<current) loop below
    // exited after exactly one round, before any real multi-round refinement happened. Force the
    // baseline low instead when seeded, so the loop's continuation is governed entirely by hash1's
    // own returned counts each round, exactly as in the unseeded/vanilla case.
    currentMaxColors[rank] = (seedColours != NULL) ? 0 : maxColor(graphData, vertexCount, true);
    parallel_flat_hash_map<std::string, ColorCount*, std::hash<string>,
                           std::equal_to<string>,
                           std::allocator<std::pair<const string, ColorCount*>>,
                           4,
                           std::mutex>
        colorMap;
    int iteration = 0;
    int currentColorCount = GetColorCount(currentMaxColors,worldSize);
    int prevColorCount = GetColorCount(maxAs, worldSize);
    while (prevColorCount < currentColorCount)
    {
        iteration++;
        #ifdef MAX_COLOR_VALUES_AND_ITER
        std::cout << "Max color: " << currentColorCount << std::endl;
        std::cout << "Max color for rank: " << currentMaxColors[rank] << std::endl;
        std::cout << "Iteration: " << iteration << std::endl << std::endl;
        #endif
        double seconds;
        #ifdef HASH1_TIME_DEBUG
        clock_t before = clock() / (CLOCKS_PER_SEC / 1000);
        auto b = std::chrono::high_resolution_clock::now();
        #endif
        HashResult result;
        if(RowCountsMatchForThisPartition(graphData, vertexCount))
        {
            result = hash1(graphData,
                &colorMap,
                vertexCount,
                rank,
                worldSize,
                totalBinWork, 
                iteration);
        }
        else
        {
            std::cout << "Row or Column counts between graph1 and graph2 don't match for rank " << rank << "." << std::endl;
            isIsomorphic[rank] = 0;
        }
        #ifdef HASH1_TIME_DEBUG
        clock_t after = clock() / (CLOCKS_PER_SEC / 1000);
        auto a = std::chrono::high_resolution_clock::now();
        std::cout << "hash1 time - cpu time " << to_string((double)(after - before) / 1000.0) << std::endl;
        std::cout << "hash1 time - wall time " << getTimeDiff(b, a) << std::endl;   
        #endif
        #ifdef MATCH_CHECK_TIMES
        clock_t beforeMatchCheck = clock() / (CLOCKS_PER_SEC / 1000);
        auto b_matchCheck = std::chrono::high_resolution_clock::now();
        #endif
        currentMaxColors[rank] = result.maxColor;
        if (!result.matchingGraphs)
        {
            // std::cout << "rank " << rank << "doesn't match " << std::endl;
            isIsomorphic[rank] = 0;
        }
        #ifdef MATCH_CHECK_TIMES
        clock_t afterMatchCheck = clock() / (CLOCKS_PER_SEC / 1000);
        auto a_matchCheck = std::chrono::high_resolution_clock::now();
        std::cout << "match check - cpu time " << to_string((double)(afterMatchCheck - beforeMatchCheck) / 1000.0) << std::endl;
        std::cout << "match check - wall time " << getTimeDiff(b_matchCheck, a_matchCheck) << std::endl;   
        #endif
        #ifdef BROADCAST_RESULT_TIMES
        clock_t beforeBroadcasts = clock() / (CLOCKS_PER_SEC / 1000);
        auto b_broadcasts = std::chrono::high_resolution_clock::now();
        #endif
        for (int i = 0; i < worldSize; i++)
        {
            MPI_Bcast(isIsomorphic + i, 1 * sizeof(unsigned char), MPI_BYTE, i, MPI_COMM_WORLD);
        }
        isomorphic = IsIsomorphic(isIsomorphic, worldSize);
        if (!isomorphic)
        {
            break;
        }
        for (int i = 0; i < worldSize; i++)
        {
            MPI_Bcast(currentMaxColors + i, 1, MPI_INT, i, MPI_COMM_WORLD);
        }
        #ifdef BROADCAST_RESULT_TIMES
        clock_t afterBroadcasts = clock() / (CLOCKS_PER_SEC / 1000);
        auto a_broadcasts = std::chrono::high_resolution_clock::now();
        OUT << "broadcasts - cpu time " << to_string((double)(afterBroadcasts - beforeBroadcasts) / 1000.0) << std::endl;
        OUT << "broadcasts - wall time " << getTimeDiff(b_broadcasts, a_broadcasts) << std::endl;   
        #endif
        SWAP_PTRS(graphData->colors1CurrentCols, graphData->colors1NextCols)
        SWAP_PTRS(graphData->colors1CurrentRows, graphData->colors1NextRows)
        SWAP_PTRS(graphData->colors2CurrentCols, graphData->colors2NextCols)
        SWAP_PTRS(graphData->colors2CurrentRows, graphData->colors2NextRows)
        graphData = InternodeLoadBalance(graphData, vertexCount, rank, worldSize, iteration, minMaxLoadDelta, averageAbsDeviation, prevTargetLoad);
        initializeNextColors(graphData->colors1NextCols, graphData->cols1.size() * vertexCount);
        initializeNextColors(graphData->colors1NextRows, graphData->rows1.size() * vertexCount);
        initializeNextColors(graphData->colors2NextCols, graphData->cols2.size() * vertexCount);
        initializeNextColors(graphData->colors2NextRows, graphData->rows2.size() * vertexCount);
        prevColorCount = currentColorCount;
        currentColorCount = GetColorCount(currentMaxColors,worldSize);
    }
#ifdef MPI_DEBUG
    std::cout << "rank  " << rank << " finished " << std::endl;
#endif
#ifdef TOTAL_BIN_WORK
    std::cout << "rank " << rank << " total bin work " << totalBinWork << " after " << iteration << "iterations" << std::endl;
#endif
    if (outputPath != NULL && rank == 0)
    {
        // graphData->allRowColors1[v] holds vertex v's stable 2WL row-colour once the while loop's
        // "prevColorCount < currentColorCount" condition goes false -- the last hash1() call already
        // wrote it (see the `graphData->allRowColors1[originalVertexId] = colourValue` assignment
        // deep in hash1's single-process branch), and nothing after that point in the loop body
        // mutates it again before falling through to here. Graph1 and graph2 are the SAME graph in
        // this patch's self-comparison use case, so allRowColors1 alone is the whole answer.
        std::ofstream outFile(outputPath);
        for (int v = 0; v < vertexCount; v++)
        {
            outFile << v << " " << graphData->allRowColors1[v] << "\n";
        }
        outFile.close();
    }
    delete [] maxAs;
    delete [] currentMaxColors;
    delete [] isIsomorphic;
    delete graphData;
    return isomorphic;
}

bool TimedTwoWL(const char *g1filename, const char *g2filename, int vertexCount, int *g1, int *g2, int rank, int worldSize)
{
    double seconds;
    clock_t before = clock() / (CLOCKS_PER_SEC / 1000);
    auto b = std::chrono::high_resolution_clock::now();
    bool result = TwoWL(g1filename, g2filename, vertexCount, g1, g2, rank, worldSize);
    auto a = std::chrono::high_resolution_clock::now();
    clock_t after = clock() / (CLOCKS_PER_SEC / 1000);
#ifdef TOTAL_TIME_DEBUG
    std::cout << "Total clock time: " << to_string((double)(after - before) / 1000.0) << std::endl;
    std::cout << "Total wall time: " << getTimeDiff(b, a) << std::endl;
#endif
#ifdef EXCEL_OUTPUT
    if (rank == 0)
    {
        std::cout << (g1filename == NULL ? "User graph 1" : g1filename)
                  << "," << (g2filename == NULL ? "User graph 2" : g2filename)
                  << "," << vertexCount 
                  << "," << worldSize 
                  << "," << omp_get_num_procs() 
                  << "," << omp_get_max_threads() 
                  << "," << getTimeDiff(b, a) 
                  << (result ? ",true" : ",false") << std::endl;
    }
#endif
    return result;
}

bool CheckLoaderCorrectness(std::vector<int>& dim1, std::vector<int> dim2, int* colorsDim1, int* colorsDim2, int vertexCount, std::string dimLabel, int rank)
{
    if(dim1.size() != dim2.size())
    {
        std::cout << dim1.size() << " " << dim2.size() << std::endl;
        std::cout << dimLabel << " and " << dimLabel << " size does not match between dense and sparse on rank " << rank << "." << std::endl;
    }
    else
    {
        bool dimsMatch = true;
        for(int i = 0; i < dim1.size() * vertexCount; i++)
        {
            if(colorsDim1[i] != colorsDim2[i])
            {
                dimsMatch = false;
            }
        }
        if(!dimsMatch)
        {
            std::cout << dimLabel << " does not match between loaders on rank " << rank << "." << std::endl;
        }
        else
        {
            std::cout << dimLabel << " matches between loaders on rank " << rank<< "." << std::endl;
        }
    }
}

int main(int argc, char *argv[])
{
    // Forces omp_get_max_threads()==1 everywhere -- works around a confirmed, reproducible
    // pre-existing upstream memory-corruption bug (double-free inside ComputeBestTeam's
    // std::deque<int> team-building logic, in the ORIGINAL unmodified scawl.cpp too, not
    // introduced by this patch) that only manifests when numThreads > 1. This project's use case
    // (single MPI rank, dialysis-sized graphs) has no need for the multi-threaded HPC path anyway.
    omp_set_num_threads(1);
    int providedSupport;
    MPI_Init_thread(NULL, NULL, MPI_THREAD_MULTIPLE, &providedSupport);
    if (providedSupport != MPI_THREAD_MULTIPLE)
    {
        std::cout << "We didn't get the desired thread support" << std::endl;
    }
    // Get the number of processes
    int world_size;
    MPI_Comm_size(MPI_COMM_WORLD, &world_size);

    // Get the rank of the process
    int world_rank;
    MPI_Comm_rank(MPI_COMM_WORLD, &world_rank);

    // Get the name of the processor
    char processor_name[MPI_MAX_PROCESSOR_NAME];
    int name_len;
    MPI_Get_processor_name(processor_name, &name_len);
    
    #ifdef USE_FILE_OUTPUT
    char hostname[1000];
    gethostname(hostname, 1000);
    std::string filename = std::string("output_") + hostname + "_rank_" + std::to_string(world_rank) + ".out";
    pOutputFile = new std::ofstream(filename);
    OUT << "Hello from " << hostname << ", rank " << world_rank << "!" << std::endl;
    #endif    
    
// Print off a hello world message
#ifdef MPI_DEBUG
    printf("Hello world from processor %s, rank %d out of %d processors\n",
           processor_name, world_rank, world_size);
#endif
    if (argc == 4)
    {
        // dialysis project's seeded single-graph mode: <graph.mtx> <seedColourFile> <outputFile>.
        // Self-comparison trick -- feeds the SAME graph as both "graph1" and "graph2" of ScaWL's
        // own pairwise machinery (an already-exercised path, see this file's default/no-arg main()
        // branch further down, which does exactly this for its own correctness self-check) so the
        // untouched refinement/hash1 internals need no further changes; only the seed-colour offset
        // (applied once, at load time) and the final allRowColors1 dump are new.
        const char *graphFile = argv[1];
        const char *seedFile = argv[2];
        const char *outFile = argv[3];
        fstream gfile(graphFile);
        if (!gfile.good())
        {
            std::cout << "graph file argument " << graphFile << " not found." << std::endl;
            MPI_Finalize();
            return 1;
        }
        int nrow, ncol;
        read_matrix_market_size(graphFile, nrow, ncol);
        int *seedColours = loadSeedColours(seedFile, nrow);
        TwoWL(graphFile, graphFile, nrow, NULL, NULL, world_rank, world_size, seedColours, outFile);
        delete[] seedColours;
        MPI_Finalize();
        return 0;
    }
    if (argc == 3)
    {
        fstream file1(argv[1]);
        fstream file2(argv[2]);
        if(!file1.good())
        {
            std::cout << "first file argument " << argv[1] << " not found." << std::endl;
            MPI_Finalize();
            return 1;
        }
        if(!file2.good())
        {
            std::cout << "second file argument " << argv[2] << " not found." << std::endl;
            MPI_Finalize();
            return 1;
        }
        int nrow1, ncol1;
        int nrow2, ncol2;
        read_matrix_market_size(argv[1], nrow1, ncol1);
        read_matrix_market_size(argv[2], nrow2, ncol2);
    
        bool result = (nrow1 == ncol1 && nrow2 == ncol2 && nrow1 == nrow2);
        if (!result)
        {
#ifdef RESULT_DEBUG
            std::cout << "Graph " << argv[1] << " and " << argv[2] << " are not isomorphic";
#endif
        }
        result = TimedTwoWL(argv[1], argv[2], nrow1, NULL, NULL, world_rank, world_size);
#ifdef RESULT_DEBUG
        std::cout << "Graph " << argv[1] << " and Graph " << argv[2] << ":" << (result ? "true" : "false") << std::endl;
#endif
    }
    else
    {
        
        int nrow1, ncol1;
        int nrow2, ncol2;
        int vertexCount;
        read_matrix_market_size("lesmis.mtx", vertexCount, vertexCount);
        int *g1 = Graph("lesmis.mtx", &nrow1, &ncol1);
        int *g2 = Graph("lesmis.mtx", &nrow2, &ncol2);
        bool result = (nrow1 == ncol1 && nrow2 == ncol2 && nrow1 == nrow2);

        GraphData* sparse = initializeFromSparseLinear("lesmis.mtx", "lesmis.mtx", vertexCount, world_rank, world_size);
        GraphData* dense = initializeFromSparse("lesmis.mtx", "lesmis.mtx", vertexCount, world_rank, world_size);
        //GraphData* dense = initializeFromDense(g1, g2, vertexCount, world_rank, world_size);

        CheckLoaderCorrectness(sparse->cols1, dense->cols1, sparse->colors1CurrentCols, dense->colors1CurrentCols, vertexCount, "cols1", world_rank);
        CheckLoaderCorrectness(sparse->cols2, dense->cols2, sparse->colors2CurrentCols, dense->colors2CurrentCols, vertexCount, "cols2", world_rank);
        CheckLoaderCorrectness(sparse->rows1, dense->rows1, sparse->colors1CurrentRows, dense->colors1CurrentRows, vertexCount, "rows1", world_rank);
        CheckLoaderCorrectness(sparse->rows2, dense->rows2, sparse->colors2CurrentRows, dense->colors2CurrentRows, vertexCount, "rows2", world_rank);
    }
    #ifdef USE_FILE_OUTPUT
    pOutputFile->close();
    delete pOutputFile; // Clean up the dynamically allocated ofstream
    #endif
    MPI_Finalize();
    return 0;
}
