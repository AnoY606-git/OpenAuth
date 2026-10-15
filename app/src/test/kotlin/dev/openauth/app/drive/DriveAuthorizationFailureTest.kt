package dev.openauth.app.drive

import com.google.android.gms.common.api.CommonStatusCodes
import org.junit.Assert.assertEquals
import org.junit.Test

class DriveAuthorizationFailureTest {
    @Test fun temporaryGoogleFailuresRemainRetryable() {
        for (code in listOf(CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.TIMEOUT, CommonStatusCodes.INTERNAL_ERROR)) {
            assertEquals(DriveOAuthFailure.RETRYABLE, classifyDriveOAuthFailure(code))
        }
    }
    @Test fun cancellationConfigurationAndSignInRemainDistinct() {
        assertEquals(DriveOAuthFailure.CANCELLED, classifyDriveOAuthFailure(CommonStatusCodes.CANCELED))
        assertEquals(DriveOAuthFailure.CONFIGURATION, classifyDriveOAuthFailure(CommonStatusCodes.DEVELOPER_ERROR))
        assertEquals(DriveOAuthFailure.DENIED, classifyDriveOAuthFailure(CommonStatusCodes.SIGN_IN_REQUIRED))
        assertEquals(DriveOAuthFailure.DENIED, classifyDriveOAuthFailure(null))
    }
}
