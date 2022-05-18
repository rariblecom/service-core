package com.rarible.core.content.meta.loader.ipfs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IpfsUrlResolverTest {

    private val service = IpfsUrlResolver(
        ipfsPublicGateway = IPFS_PUBLIC_GATEWAY,
        ipfsInnerGateways = IPFS_PUBLIC_GATEWAY,
        ipfsCustomGateways = IPFS_CUSTOM_GATEWAY
    )

    @Test
    fun `is cid`() {
        assertThat(service.isCid("QmQzqPpcBFkc9AwV4B2tscLy9dBwN7o9yEHE9aRCHeN6KW")).isTrue()
        assertThat(service.isCid("QQzqPpcBFkc9AwV4B2tscLy9dBwN7o9yEHE9aRCHeN6KW")).isFalse()
        assertThat(service.isCid("bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi")).isTrue()
        assertThat(service.isCid("3")).isFalse()
        assertThat(service.isCid("f01701220c3c4733ec8affd06cf9e9ff50ffc6bcd2ec85a6170004bb709669c31de94391a"))
            .isTrue()
    }

    @Test
    fun `svg file with CID urls`() {
        val svg = "<svg url=https://ipfs.io/ipfs/QmQzqPpcBFkc9AwV4B2tscLy9dBwN7o9yEHE9aRCHeN6KW></svg>"
        val result = service.resolvePublicHttpUrl(svg)
        // should stay as SVG
        assertThat(result).isEqualTo(svg)
    }

    @Test
    fun `foreign ipfs urls - replaced by public gateway`() {
        // Broken IPFS URL
        assertFixedIpfsUrl("htt://mypinata.com/ipfs/$CID", CID)
        // Relative IPFS path
        assertFixedIpfsUrl("/ipfs/$CID/abc .png", "$CID/abc%20.png")

        // Abstract IPFS urls with /ipfs/ path and broken slashes
        assertFixedIpfsUrl("ipfs:/ipfs/$CID", CID)
        assertFixedIpfsUrl("ipfs://ipfs/$CID", CID)
        assertFixedIpfsUrl("ipfs:///ipfs/$CID", CID)
        assertFixedIpfsUrl("ipfs:////ipfs/$CID", CID)

        assertFixedIpfsUrl("ipfs:////ipfs/$CID", CID)
        assertFixedIpfsUrl("ipfs:////ipfs//$CID", CID)
        assertFixedIpfsUrl("ipfs:////ipfs///$CID", CID)
    }

    @Test
    fun `foreign ipfs urls - original gateway kept`() {
        // Regular IPFS URL
        assertOriginalIpfsUrl("https://ipfs.io/ipfs/$CID")
        // Regular IPFS URL with 2 /ipfs/ parts
        assertOriginalIpfsUrl("https://ipfs.io/ipfs/something/ipfs/$CID")
        // Regular IPFS URL but without CID
        assertOriginalIpfsUrl("http://ipfs.io/ipfs/123.jpg")
    }

    @Test
    fun `prefixed ipfs urls`() {
        assertFixedIpfsUrl("ipfs:/folder/$CID/abc .json", "folder/$CID/abc%20.json")
        assertFixedIpfsUrl("ipfs://folder/abc", "folder/abc")
        assertFixedIpfsUrl("ipfs:///folder/subfolder/$CID", "folder/subfolder/$CID")
        assertFixedIpfsUrl("ipfs:////$CID", CID)

        // Various case of ipfs prefix
        assertFixedIpfsUrl("IPFS://$CID", CID)
        assertFixedIpfsUrl("Ipfs:///$CID", CID)

        // Abstract IPFS urls with /ipfs/ path and broken slashes without a CID
        assertFixedIpfsUrl("ipfs:/ipfs/abc", "abc")
        assertFixedIpfsUrl("ipfs://ipfs/folder/abc", "folder/abc")
        assertFixedIpfsUrl("ipfs:///ipfs/abc", "abc")
    }

    @Test
    fun `foreign ipfs urls - replaced by internal gateway`() {
        val result = service.resolveInnerHttpUrl("https://dweb.link/ipfs/${CID}/1.png")
        assertThat(result)
            .isEqualTo("${IPFS_PUBLIC_GATEWAY}/ipfs/${CID}/1.png")
    }

    @Test
    fun `single sid`() {
        assertFixedIpfsUrl(CID, CID)
    }

    @Test
    fun `regular url`() {
        val https = "https://api.t-o-s.xyz/ipfs/gucci/8.gif"
        val http = "http://api.guccinfts.xyz/ipfs/8"

        assertThat(service.resolvePublicHttpUrl(http)).isEqualTo(http)
        assertThat(service.resolvePublicHttpUrl(https)).isEqualTo(https)
    }

    @Test
    fun `some ipfs path`() {
        val path = "///hel lo.png"
        assertThat(service.resolvePublicHttpUrl(path))
            .isEqualTo("${IPFS_PUBLIC_GATEWAY}/hel%20lo.png")
    }

    @Test
    fun `replace legacy`() {
        assertThat(service.resolveInnerHttpUrl("${IPFS_CUSTOM_GATEWAY}/ipfs/abc"))
            .isEqualTo("${IPFS_PUBLIC_GATEWAY}/ipfs/abc")
    }

    private fun assertFixedIpfsUrl(url: String, expectedPath: String) {
        val result = service.resolvePublicHttpUrl(url)
        assertThat(result).isEqualTo("${IPFS_PUBLIC_GATEWAY}/ipfs/$expectedPath")
    }

    private fun assertOriginalIpfsUrl(url: String, expectedPath: String? = null) {
        val expected = expectedPath ?: url // in most cases we expect URL not changed
        val result = service.resolvePublicHttpUrl(url)
        assertThat(result).isEqualTo(expected)
    }

    companion object {
        private const val IPFS_PUBLIC_GATEWAY = "https://ipfs.io"
        private const val IPFS_CUSTOM_GATEWAY = "https://rarible.mypinata.com" // Legacy
        private const val CID = "QmbpJhWFiwzNu7MebvKG3hrYiyWmSiz5dTUYMQLXsjT9vw"
    }
}
