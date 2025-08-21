USE [webapp]
GO

SET ANSI_NULLS ON
GO

SET QUOTED_IDENTIFIER ON
GO

CREATE TABLE [dbo].[sessions](
	[user_id] [uniqueidentifier] NOT NULL,
	[token] [nvarchar](256) NOT NULL,
	[created_at] [datetimeoffset](7) NOT NULL,
	[expires_at] [datetimeoffset](7) NOT NULL
) ON [PRIMARY]
GO

ALTER TABLE [dbo].[sessions]  WITH CHECK ADD FOREIGN KEY([user_id])
REFERENCES [dbo].[users] ([id])
GO