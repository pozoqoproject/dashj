#ifndef HASHBLOCK_H
#define HASHBLOCK_H

#include "sph_blake.h"
#include "sph_groestl.h"
#include "sph_cubehash.h"
#include "sph_shavite.h"
#include "sph_echo.h"

#define HASH512_SIZE 64
#define HASH256_SIZE 32

void trim256(const unsigned char * pn, unsigned char * ret)
{
    for (unsigned int i = 0; i < HASH256_SIZE; i++){
        ret[i] = pn[i];
    }
}

inline bool HashX11(const unsigned char * pbegin, const unsigned char * pend, unsigned char * pResult)
{
    sph_blake512_context     ctx_blake;
    sph_groestl512_context   ctx_groestl;
    sph_cubehash512_context  ctx_cubehash;
    sph_shavite512_context   ctx_shavite;
    sph_echo512_context      ctx_echo;
    static unsigned char pblank[1];

    unsigned char hash[5][HASH512_SIZE];

    sph_blake512_init(&ctx_blake);
    sph_blake512 (&ctx_blake, (pbegin == pend ? pblank : static_cast<const void*>(&pbegin[0])), (pend - pbegin) * sizeof(pbegin[0]));
    sph_blake512_close(&ctx_blake, static_cast<void*>(&hash[0]));

    sph_groestl512_init(&ctx_groestl);
    sph_groestl512 (&ctx_groestl, static_cast<const void*>(&hash[0]), 64);
    sph_groestl512_close(&ctx_groestl, static_cast<void*>(&hash[1]));

    sph_cubehash512_init(&ctx_cubehash);
    sph_cubehash512 (&ctx_cubehash, static_cast<const void*>(&hash[1]), 64);
    sph_cubehash512_close(&ctx_cubehash, static_cast<void*>(&hash[2]));
    
    sph_shavite512_init(&ctx_shavite);
    sph_shavite512(&ctx_shavite, static_cast<const void*>(&hash[2]), 64);
    sph_shavite512_close(&ctx_shavite, static_cast<void*>(&hash[3]));

    sph_echo512_init(&ctx_echo);
    sph_echo512 (&ctx_echo, static_cast<const void*>(&hash[3]), 64);
    sph_echo512_close(&ctx_echo, static_cast<void*>(&hash[4]));

    trim256(hash[4], pResult);
    return true;
}



#endif // HASHBLOCK_H
