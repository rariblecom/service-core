package com.rarible.core.content.meta.loader.ipfs

import com.rarible.core.content.meta.loader.ipfs.checker.AbstractIpfsUrlChecker
import com.rarible.core.content.meta.loader.ipfs.checker.EmbeddedImageChecker
import com.rarible.core.content.meta.loader.ipfs.checker.ForeignerIpfsUriChecker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IpfsUrlResolverTest {

    private val embeddedImageChecker = EmbeddedImageChecker(
        embeddedImageFilters = listOf(EmbeddedSvgFilter())
    )
    private val cidResolver = CidResolver()
    private val foreignerIpfsUriChecker = ForeignerIpfsUriChecker(
        customGatewaysResolver = RandomGatewayResolver(IPFS_CUSTOM_GATEWAY),
        cidResolver = cidResolver
    )

    private val resolver = IpfsUrlResolver(
        publicGatewayResolver = RandomGatewayResolver(IPFS_PUBLIC_GATEWAY),
        innerGatewaysResolver = RandomGatewayResolver(IPFS_PUBLIC_GATEWAY),
        embeddedImageChecker = embeddedImageChecker,
        foreignerIpfsUriChecker = foreignerIpfsUriChecker,
        abstractIpfsUrlChecker = AbstractIpfsUrlChecker()
    )

    @Test
    fun `svg file with CID urls`() {
        val svg = "<svg url=https://ipfs.io/ipfs/QmQzqPpcBFkc9AwV4B2tscLy9dBwN7o9yEHE9aRCHeN6KW></svg>"
        val result = resolver.resolvePublicHttpUrl(svg)
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
        val result = resolver.resolveInnerHttpUrl("https://dweb.link/ipfs/${CID}/1.png")
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

        assertThat(resolver.resolvePublicHttpUrl(http)).isEqualTo(http)
        assertThat(resolver.resolvePublicHttpUrl(https)).isEqualTo(https)
    }

    @Test
    fun `some ipfs path`() {
        val path = "///hel lo.png"
        assertThat(resolver.resolvePublicHttpUrl(path))
            .isEqualTo("${IPFS_PUBLIC_GATEWAY}/hel%20lo.png")
    }

    @Test
    fun `replace legacy`() {
        assertThat(resolver.resolveInnerHttpUrl("${IPFS_CUSTOM_GATEWAY}/ipfs/abc"))
            .isEqualTo("${IPFS_PUBLIC_GATEWAY}/ipfs/abc")
    }

    private fun assertFixedIpfsUrl(url: String, expectedPath: String) {
        val result = resolver.resolvePublicHttpUrl(url)
        assertThat(result).isEqualTo("${IPFS_PUBLIC_GATEWAY}/ipfs/$expectedPath")
    }

    private fun assertOriginalIpfsUrl(url: String, expectedPath: String? = null) {
        val expected = expectedPath ?: url // in most cases we expect URL not changed
        val result = resolver.resolvePublicHttpUrl(url)
        assertThat(result).isEqualTo(expected)
    }

    companion object {
        const val IPFS_PUBLIC_GATEWAY = "https://ipfs.io"
        private const val IPFS_CUSTOM_GATEWAY = "https://rarible.mypinata.com" // Legacy
        private const val CID = "QmbpJhWFiwzNu7MebvKG3hrYiyWmSiz5dTUYMQLXsjT9vw"
    }
}
